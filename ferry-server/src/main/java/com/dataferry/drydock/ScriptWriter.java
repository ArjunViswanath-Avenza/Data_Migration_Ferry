package com.dataferry.drydock;

import com.dataferry.drydock.SchemaComparer.Comparison;
import com.dataferry.drydock.SchemaComparer.Difference;
import com.dataferry.drydock.SchemaModel.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Turns a comparison plus the operator's decisions into two scripts:
 *
 * <ol>
 *   <li><b>Refit</b>: DDL that brings the target schema in line with the chosen source differences.
 *       Additions and changes come first; anything that removes data (DROP) is collected at the end,
 *       and only appears when the operator chose it.</li>
 *   <li><b>Data move</b>: one INSERT ... SELECT per table from the source schema into the refitted
 *       target, with renamed columns mapped, values for new NOT NULL columns, and a comment on every
 *       column whose values could be cut or rejected.</li>
 * </ol>
 *
 * Nothing is run. The scripts are text for a DBA to read, adjust and run.
 */
final class ScriptWriter {

    public record Decision(String action, List<String> aspects) {}

    public record Options(String sourceSchema, String targetSchema, String dataMode, boolean emptyTargetFirst, List<String> excludeTables) {
        public Options {
            if (dataMode == null || dataMode.isBlank()) dataMode = "INSERT";
            dataMode = dataMode.toUpperCase(Locale.ROOT);
            if (!Set.of("INSERT", "INSERT_IGNORE", "REPLACE", "UPSERT").contains(dataMode))
                throw new IllegalArgumentException("Unknown data mode " + dataMode + ". Use INSERT, INSERT_IGNORE, REPLACE or UPSERT.");
            if (excludeTables == null) excludeTables = List.of();
            sourceSchema = clean(sourceSchema);
            targetSchema = clean(targetSchema);
        }

        private static String clean(String s) {
            if (s == null || s.isBlank()) return null;
            if (!s.matches("[A-Za-z0-9_$]{1,64}")) throw new IllegalArgumentException("Schema names may use letters, digits, _ and $ only: " + s);
            return s;
        }
    }

    public record Result(String refit, String dataMove, List<String> warnings, int refitStatements, int dataStatements,
                         int removals, Map<String, String> actions) {}

    // ------------------------------------------------------------------

    private final Comparison cmp;
    private final Map<String, Decision> decisions;
    private final Options opt;
    private final SchemaModel src, tgt;

    private final Map<String, Table> fin = new LinkedHashMap<>();           // the target as it will be, by lower-case name
    private final Map<String, String> tableMap = new LinkedHashMap<>();     // source table key -> final table key
    private final Map<String, Map<String, String>> colMap = new HashMap<>(); // final table key -> (final column lower -> source column)
    private final Map<String, String> actions = new LinkedHashMap<>();
    private final List<String> warnings = new ArrayList<>();

    private final List<String> sFkDrops = new ArrayList<>(), sRenames = new ArrayList<>(), sCreates = new ArrayList<>(),
            sAlters = new ArrayList<>(), sFkAdds = new ArrayList<>(), sRoutines = new ArrayList<>(), sRemovals = new ArrayList<>();

    private ScriptWriter(Comparison cmp, Map<String, Decision> decisions, Options opt) {
        this.cmp = cmp;
        this.decisions = decisions == null ? Map.of() : decisions;
        this.opt = opt;
        this.src = cmp.sourceSchema;
        this.tgt = cmp.targetSchema;
    }

    static Result write(Comparison cmp, Map<String, Decision> decisions, Options opt) {
        return new ScriptWriter(cmp, decisions, opt).run();
    }

    private String action(Difference d) {
        Decision dec = decisions.get(d.id);
        String a = dec != null && dec.action() != null && d.actions.contains(dec.action().toUpperCase(Locale.ROOT))
                ? dec.action().toUpperCase(Locale.ROOT) : d.defaultAction;
        actions.put(d.id, a);
        return a;
    }

    private Set<String> aspects(Difference d) {
        Decision dec = decisions.get(d.id);
        if (dec != null && dec.aspects() != null) return new HashSet<>(dec.aspects());
        Set<String> def = d.aspects.stream().filter(SchemaComparer.Aspect::applyByDefault).map(SchemaComparer.Aspect::key).collect(Collectors.toSet());
        return def.isEmpty() ? d.aspects.stream().map(SchemaComparer.Aspect::key).collect(Collectors.toSet()) : def;
    }

    private Result run() {
        tgt.tables.forEach((k, t) -> fin.put(k, t.copy(t.name)));
        Map<String, List<Difference>> byTable = cmp.differences.stream().filter(d -> d.tableKey != null && isTableArea(d.area))
                .collect(Collectors.groupingBy(d -> d.tableKey, LinkedHashMap::new, Collectors.toList()));

        // ---- tables: pairs, creations, renames
        for (String k : src.tables.keySet()) if (tgt.tables.containsKey(k)) tableMap.put(k, k);
        Set<String> separated = new HashSet<>();
        for (Difference d : cmp.differences) {
            if (!d.area.equals("TABLE")) continue;
            String a = action(d);
            switch (d.kind) {
                case "ONLY_IN_SOURCE" -> {
                    if (a.equals("CREATE")) createFromSource(src.table(d.sourceName));
                    else warnings.add("Table " + d.sourceName + " is not created, so its rows are not moved.");
                }
                case "ONLY_IN_TARGET" -> {
                    if (a.equals("DROP")) {
                        sRemovals.add("-- Removes table " + d.targetName + " and every row in it.\nDROP TABLE IF EXISTS " + Sql.q(d.targetName) + ";");
                        fin.remove(SchemaModel.key(d.targetName));
                    }
                }
                case "RENAMED" -> {
                    String sk = SchemaModel.key(d.sourceName), tk = SchemaModel.key(d.targetName);
                    switch (a) {
                        case "MAP" -> tableMap.put(sk, tk);
                        case "RENAME" -> {
                            if (fin.containsKey(sk)) { warnings.add("Cannot rename " + d.targetName + " to " + d.sourceName + ": that name is taken. Mapped instead."); tableMap.put(sk, tk); break; }
                            Table t = fin.remove(tk);
                            t.name = d.sourceName;
                            fin.put(sk, t);
                            tableMap.put(sk, sk);
                            sRenames.add("-- " + d.targetName + " takes the source name.\nRENAME TABLE " + Sql.q(d.targetName) + " TO " + Sql.q(d.sourceName) + ";");
                        }
                        default -> {   // SEPARATE
                            separated.add(tk);
                            if (fin.containsKey(sk)) warnings.add("Table " + d.sourceName + " already exists in the target; not created.");
                            else createFromSource(src.table(d.sourceName));
                        }
                    }
                }
                default -> { }
            }
        }

        // ---- per table: columns, keys, indexes, options
        for (Map.Entry<String, List<Difference>> e : byTable.entrySet()) {
            String key = e.getKey();
            if (separated.contains(key)) continue;
            String sourceKey = sourceKeyFor(key);
            Table s = sourceKey == null ? null : src.tables.get(sourceKey);
            String finKey = sourceKey == null ? key : tableMap.getOrDefault(sourceKey, key);
            Table f = fin.get(finKey);
            if (s == null || f == null) continue;
            alterTable(f, finKey, s, e.getValue());
        }
        // identity column maps for every pair with no differences of their own
        tableMap.forEach((sk, fk) -> colMap.computeIfAbsent(fk, x -> identity(fin.get(fk), src.tables.get(sk))));
        pendingFks.forEach(p -> resolveForeignKey(p.owner(), p.fk(), p.why()));

        // ---- routines
        for (Difference d : cmp.differences) {
            if (isTableArea(d.area) || d.area.equals("TABLE")) continue;
            String a = action(d);
            Routine sr = d.sourceName == null ? null : src.routines.get(d.area + ":" + SchemaModel.key(d.sourceName));
            if ((a.equals("CREATE") || a.equals("REPLACE")) && sr != null) sRoutines.add(staleNames(sr) + routine(sr));
            if (a.equals("DROP")) sRemovals.add("-- Removes " + d.area.toLowerCase(Locale.ROOT) + " " + d.targetName + ".\nDROP " + d.area + " IF EXISTS " + Sql.q(d.targetName) + ";");
        }

        String refit = refitScript();
        String data = dataScript();
        int refitCount = sFkDrops.size() + sRenames.size() + sCreates.size() + sAlters.size() + sFkAdds.size() + sRoutines.size() + sRemovals.size();
        return new Result(refit, data, warnings, refitCount, dataCount, sRemovals.size(), actions);
    }

    /** A routine copied from the source may name tables that have another name in the target. */
    private String staleNames(Routine r) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : tableMap.entrySet()) {
            Table s = src.tables.get(e.getKey()), f = fin.get(e.getValue());
            if (s == null || f == null || s.name.equalsIgnoreCase(f.name)) continue;
            if (java.util.regex.Pattern.compile("(?i)(?<![\\w$])`?" + java.util.regex.Pattern.quote(s.name) + "`?(?![\\w$])").matcher(r.sql).find()) {
                sb.append("-- CHECK: this ").append(r.kind.toLowerCase(Locale.ROOT)).append(" names table ").append(s.name)
                        .append(", which is ").append(f.name).append(" in the target. Edit it before running.\n");
                warnings.add(r.kind.charAt(0) + r.kind.substring(1).toLowerCase(Locale.ROOT) + " " + r.name + " names table " + s.name
                        + ", which is " + f.name + " in the target; edit it in the refit script.");
            }
        }
        return sb.toString();
    }

    private static boolean isTableArea(String area) {
        return Set.of("COLUMN", "PRIMARY_KEY", "INDEX", "FOREIGN_KEY", "TABLE_OPTIONS").contains(area);
    }

    private String sourceKeyFor(String targetKey) {
        for (Difference d : cmp.differences)
            if (d.area.equals("TABLE") && d.kind.equals("RENAMED") && d.tableKey.equals(targetKey)) return SchemaModel.key(d.sourceName);
        return src.tables.containsKey(targetKey) ? targetKey : null;
    }

    private void createFromSource(Table s) {
        Table t = s.copy(s.name);
        String k = SchemaModel.key(s.name);
        Map<String, ForeignKey> fks = new LinkedHashMap<>(t.foreignKeys);
        t.foreignKeys.clear();
        fin.put(k, t);
        tableMap.put(k, k);
        sCreates.add("-- New table from the source.\n" + Sql.createTable(t, false));
        // foreign keys are added once every table exists
        for (ForeignKey fk : fks.values()) addForeignKey(t, k, fk, "Foreign key of new table " + t.name + ".");
    }

    // ------------------------------------------------------------------ one table

    private void alterTable(Table f, String finKey, Table s, List<Difference> diffs) {
        Map<String, String> cm = identity(f, s);
        colMap.put(finKey, cm);
        List<String> clauses = new ArrayList<>(), removals = new ArrayList<>();

        // renames first, so later clauses use the final names
        for (Difference d : diffs) {
            if (!(d.area.equals("COLUMN") && d.kind.equals("RENAMED"))) continue;
            String a = action(d);
            Column tc = f.column(d.targetName);
            if (tc == null) continue;
            switch (a) {
                case "MAP" -> cm.put(tc.name.toLowerCase(Locale.ROOT), d.sourceName);
                case "RENAME" -> {
                    String old = tc.name;
                    tc.name = d.sourceName;
                    renameInKeys(f, old, d.sourceName);
                    cm.remove(old.toLowerCase(Locale.ROOT));
                    cm.put(d.sourceName.toLowerCase(Locale.ROOT), d.sourceName);
                    clauses.add("  -- " + old + " takes the source name " + d.sourceName + "\n  CHANGE COLUMN " + Sql.q(old) + " " + Sql.columnDefinition(tc));
                }
                default -> addColumn(f, s, s.column(d.sourceName), cm, clauses);   // SEPARATE
            }
        }

        for (Difference d : diffs) {
            String a = action(d);
            switch (d.area) {
                case "COLUMN" -> {
                    if (d.kind.equals("ONLY_IN_SOURCE") && a.equals("ADD")) addColumn(f, s, s.column(d.sourceName), cm, clauses);
                    else if (d.kind.equals("ONLY_IN_SOURCE")) warnings.add(f.name + "." + d.sourceName + " is not added, so its values are not moved.");
                    else if (d.kind.equals("ONLY_IN_TARGET") && a.equals("DROP")) {
                        removals.add("  -- removes " + d.targetName + " and its values\n  DROP COLUMN " + Sql.q(d.targetName));
                        Column gone = f.column(d.targetName);
                        if (gone != null) f.columns.remove(gone);
                        cm.remove(d.targetName.toLowerCase(Locale.ROOT));
                    } else if (d.kind.equals("CHANGED") && a.equals("APPLY")) {
                        Column fc = f.column(d.targetName), sc = s.column(d.sourceName);
                        if (fc == null || sc == null) continue;
                        Set<String> chosen = aspects(d);
                        if (chosen.isEmpty()) continue;
                        merge(fc, sc, chosen);
                        String what = d.aspects.stream().filter(x -> chosen.contains(x.key()))
                                .map(x -> x.label().toLowerCase(Locale.ROOT) + " " + x.target() + " → " + x.source()).collect(Collectors.joining("; "));
                        clauses.add("  -- " + fc.name + ": " + what + "\n  MODIFY COLUMN " + Sql.columnDefinition(fc));
                    }
                }
                case "PRIMARY_KEY" -> {
                    if (a.equals("REPLACE")) {
                        List<String> cols = s.primaryKey.stream().map(c -> finalName(cm, c)).toList();
                        String drop = f.primaryKey.isEmpty() ? "" : "  DROP PRIMARY KEY,\n";
                        clauses.add("  -- primary key from the source\n" + drop + "  ADD PRIMARY KEY (" + Sql.indexColumns(cols) + ")");
                        f.primaryKey = new ArrayList<>(cols);
                    } else if (a.equals("DROP")) {
                        removals.add("  -- removes the primary key\n  DROP PRIMARY KEY");
                        f.primaryKey = new ArrayList<>();
                    }
                }
                case "INDEX" -> {
                    if (a.equals("ADD") || a.equals("REPLACE")) {
                        Index ix = s.indexes.get(SchemaModel.key(d.sourceName)).copy();
                        ix.columns.replaceAll(c -> c.startsWith("(") ? c : finalName(cm, DdlParser.bare(c)) + c.substring(DdlParser.bare(c).length()));
                        if (a.equals("REPLACE")) { clauses.add("  DROP INDEX " + Sql.q(d.targetName)); f.indexes.remove(SchemaModel.key(d.targetName)); }
                        clauses.add("  -- index from the source\n  ADD " + Sql.indexDefinition(ix));
                        f.indexes.put(SchemaModel.key(ix.name), ix);
                    } else if (a.equals("DROP")) {
                        removals.add("  DROP INDEX " + Sql.q(d.targetName));
                        f.indexes.remove(SchemaModel.key(d.targetName));
                    }
                }
                case "FOREIGN_KEY" -> {
                    if (a.equals("REPLACE") || a.equals("DROP")) {
                        String stmt = "ALTER TABLE " + Sql.q(f.name) + " DROP FOREIGN KEY " + Sql.q(d.targetName) + ";";
                        if (a.equals("DROP")) sRemovals.add("-- Removes foreign key " + d.targetName + " on " + f.name + ".\n" + stmt);
                        else sFkDrops.add("-- Replaced by the source's rule below.\n" + stmt);
                        f.foreignKeys.remove(SchemaModel.key(d.targetName));
                    }
                    if (a.equals("ADD") || a.equals("REPLACE")) {
                        ForeignKey fk = s.foreignKeys.get(SchemaModel.key(d.sourceName)).copy();
                        fk.columns.replaceAll(c -> finalName(cm, c));
                        addForeignKey(f, finKey, fk, "Foreign key from the source.");
                    }
                }
                case "TABLE_OPTIONS" -> {
                    if (!a.equals("APPLY")) continue;
                    Set<String> chosen = aspects(d);
                    List<String> opts = new ArrayList<>();
                    if (chosen.contains("engine") && s.engine != null) { opts.add("ENGINE=" + s.engine); f.engine = s.engine; }
                    if (chosen.contains("charset") && s.charset != null) { opts.add("DEFAULT CHARSET=" + s.charset + (s.collation != null ? " COLLATE=" + s.collation : "")); f.charset = s.charset; f.collation = s.collation; }
                    else if (chosen.contains("collation") && s.collation != null) { opts.add("COLLATE=" + s.collation); f.collation = s.collation; }
                    if (chosen.contains("comment")) opts.add("COMMENT=" + Sql.literal(s.comment == null ? "" : s.comment));
                    if (chosen.contains("rowFormat") && s.rowFormat != null) opts.add("ROW_FORMAT=" + s.rowFormat);
                    if (!opts.isEmpty()) clauses.add("  -- table options from the source\n  " + String.join(" ", opts));
                }
                default -> { }
            }
        }
        if (!clauses.isEmpty()) sAlters.add("ALTER TABLE " + Sql.q(f.name) + "\n" + String.join(",\n", clauses) + ";");
        if (!removals.isEmpty()) sRemovals.add("ALTER TABLE " + Sql.q(f.name) + "\n" + String.join(",\n", removals) + ";");
    }

    private void addColumn(Table f, Table s, Column sc, Map<String, String> cm, List<String> clauses) {
        if (sc == null) return;
        if (f.column(sc.name) != null) { warnings.add(f.name + "." + sc.name + " already exists in the target; not added again."); return; }
        Column c = sc.copy();
        // place it after the column that precedes it in the source, when the target has that column
        int si = s.indexOf(sc.name);
        String after = null;
        for (int i = si - 1; i >= 0 && after == null; i--) {
            String prev = finalNameOrNull(cm, s.columns.get(i).name);
            if (prev != null && f.column(prev) != null) after = f.column(prev).name;
        }
        String place = si == 0 ? " FIRST" : after != null ? " AFTER " + Sql.q(after) : "";
        if (si == 0) f.columns.add(0, c);
        else if (after != null) f.columns.add(f.indexOf(after) + 1, c);
        else f.columns.add(c);
        cm.put(c.name.toLowerCase(Locale.ROOT), sc.name);
        boolean needsValue = !c.nullable && c.defaultValue == null && !c.autoIncrement && c.generated == null;
        clauses.add("  -- new column from the source" + (needsValue ? " (NOT NULL, no default: existing rows get " + Types.placeholder(c) + ")" : "")
                + "\n  ADD COLUMN " + Sql.columnDefinition(c) + place);
    }

    private record PendingFk(Table owner, ForeignKey fk, String why) {}
    private final List<PendingFk> pendingFks = new ArrayList<>();

    /** Foreign keys are resolved once every table exists, renamed or not. */
    private void addForeignKey(Table f, String finKey, ForeignKey fk, String why) { pendingFks.add(new PendingFk(f, fk, why)); }

    private void resolveForeignKey(Table f, ForeignKey fk, String why) {
        String refSourceKey = SchemaModel.key(fk.refTable);
        String refFinKey = tableMap.getOrDefault(refSourceKey, refSourceKey);
        Table ref = fin.get(refFinKey);
        if (ref == null) {
            warnings.add("Foreign key " + fk.name + " on " + f.name + " refers to " + fk.refTable + ", which the target will not have; left out.");
            sFkAdds.add("-- LEFT OUT: " + fk.name + " refers to " + fk.refTable + ", which the target will not have.\n-- ALTER TABLE "
                    + Sql.q(f.name) + " ADD " + Sql.foreignKeyDefinition(fk) + ";");
            return;
        }
        Map<String, String> refMap = colMap.get(refFinKey);
        ForeignKey out = fk.copy();
        out.refTable = ref.name;
        if (refMap != null) out.refColumns.replaceAll(c -> finalName(refMap, c));
        if (nameTaken(out.name, f)) out.name = uniqueFkName(out.name);
        f.foreignKeys.put(SchemaModel.key(out.name), out);
        sFkAdds.add("-- " + why + "\nALTER TABLE " + Sql.q(f.name) + " ADD " + Sql.foreignKeyDefinition(out) + ";");
    }

    /** Foreign key names are unique per schema in MySQL. */
    private boolean nameTaken(String name, Table owner) {
        for (Table t : fin.values()) if (t.foreignKeys.containsKey(SchemaModel.key(name))) return true;
        return false;
    }

    private String uniqueFkName(String base) {
        for (int i = 1; ; i++) {
            String n = (base.length() > 56 ? base.substring(0, 56) : base) + "_src" + (i == 1 ? "" : i);
            boolean taken = false;
            for (Table t : fin.values()) if (t.foreignKeys.containsKey(SchemaModel.key(n))) taken = true;
            if (!taken) return n;
        }
    }

    private static void merge(Column f, Column s, Set<String> aspects) {
        if (aspects.contains("type")) { f.dataType = s.dataType; f.args = s.args; f.unsigned = s.unsigned; f.zerofill = s.zerofill; }
        if (aspects.contains("nullable")) f.nullable = s.nullable;
        if (aspects.contains("default")) f.defaultValue = s.defaultValue;
        if (aspects.contains("onUpdate")) f.onUpdate = s.onUpdate;
        if (aspects.contains("autoIncrement")) f.autoIncrement = s.autoIncrement;
        if (aspects.contains("charset")) { f.charset = s.charset; f.collation = s.collation; }
        if (aspects.contains("collation")) f.collation = s.collation;
        if (aspects.contains("comment")) f.comment = s.comment;
        if (aspects.contains("generated")) { f.generated = s.generated; f.generatedKind = s.generatedKind; }
    }

    private static void renameInKeys(Table t, String from, String to) {
        t.primaryKey.replaceAll(x -> x.equalsIgnoreCase(from) ? to : x);
        for (Index ix : t.indexes.values())
            ix.columns.replaceAll(x -> DdlParser.bare(x).equalsIgnoreCase(from) ? to + x.substring(DdlParser.bare(x).length()) : x);
        for (ForeignKey fk : t.foreignKeys.values()) fk.columns.replaceAll(x -> x.equalsIgnoreCase(from) ? to : x);
    }

    /** Final columns that have a same-named source column. */
    private static Map<String, String> identity(Table f, Table s) {
        Map<String, String> m = new LinkedHashMap<>();
        if (f == null || s == null) return m;
        for (Column c : f.columns) { Column sc = s.column(c.name); if (sc != null) m.put(c.name.toLowerCase(Locale.ROOT), sc.name); }
        return m;
    }

    /** The final column a source column lands in. */
    private static String finalName(Map<String, String> cm, String sourceColumn) {
        String n = finalNameOrNull(cm, sourceColumn);
        return n == null ? sourceColumn : n;
    }

    private static String finalNameOrNull(Map<String, String> cm, String sourceColumn) {
        for (Map.Entry<String, String> e : cm.entrySet()) if (e.getValue().equalsIgnoreCase(sourceColumn)) return e.getKey();
        return null;
    }

    private static String routine(Routine r) {
        String drop = "DROP " + r.kind + " IF EXISTS " + Sql.q(r.name) + ";";
        if (r.kind.equals("VIEW")) return "-- " + r.kind.toLowerCase(Locale.ROOT) + " " + r.name + " from the source\n" + drop + "\n" + r.sql + ";";
        return "-- " + r.kind.toLowerCase(Locale.ROOT) + " " + r.name + " from the source\n" + drop + "\nDELIMITER $$\n" + r.sql + " $$\nDELIMITER ;";
    }

    // ------------------------------------------------------------------ refit script

    private String refitScript() {
        StringBuilder sb = new StringBuilder();
        header(sb, "Refit script: brings the TARGET schema in line with the source differences you chose.");
        sb.append("-- Run it against the target database. Take a backup first and read every statement.\n");
        sb.append("-- Additions and changes come first; anything that removes data is in the last section.\n\n");
        if (opt.targetSchema() != null) sb.append("USE ").append(Sql.q(opt.targetSchema())).append(";\n\n");
        sb.append("SET @DRYDOCK_FK_CHECKS = @@FOREIGN_KEY_CHECKS;\nSET FOREIGN_KEY_CHECKS = 0;\n");
        section(sb, "1. Foreign keys replaced by the source's rules", sFkDrops);
        section(sb, "2. Tables renamed to the source name", sRenames);
        section(sb, "3. Tables only the source has", sCreates);
        section(sb, "4. Columns, keys, indexes and table options (one ALTER per table)", sAlters);
        section(sb, "5. Foreign keys from the source", sFkAdds);
        section(sb, "6. Views, procedures, functions and triggers from the source", sRoutines);
        section(sb, "7. Removals you chose (these delete data or objects)", sRemovals);
        sb.append("\nSET FOREIGN_KEY_CHECKS = @DRYDOCK_FK_CHECKS;\n");
        if (sFkDrops.size() + sRenames.size() + sCreates.size() + sAlters.size() + sFkAdds.size() + sRoutines.size() + sRemovals.size() == 0)
            sb.append("\n-- Nothing to change: with the current choices the target already matches.\n");
        return sb.toString();
    }

    private void header(StringBuilder sb, String what) {
        sb.append("-- ====================================================================\n");
        sb.append("-- Data Migration Ferry · Dry Dock\n");
        sb.append("-- ").append(what).append('\n');
        sb.append("-- Source (changes come from): ").append(cmp.source.fileName()).append(cmp.source.database() == null ? "" : " (" + cmp.source.database() + ")").append('\n');
        sb.append("-- Target (is changed):        ").append(cmp.target.fileName()).append(cmp.target.database() == null ? "" : " (" + cmp.target.database() + ")").append('\n');
        sb.append("-- Comparison ").append(cmp.id).append(", script written ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))).append('\n');
        sb.append("-- Nothing in this file has been run.\n");
        sb.append("-- ====================================================================\n");
    }

    private static void section(StringBuilder sb, String title, List<String> stmts) {
        if (stmts.isEmpty()) return;
        sb.append("\n-- --------------------------------------------------------------------\n-- ").append(title)
                .append(" (").append(stmts.size()).append(")\n-- --------------------------------------------------------------------\n\n");
        sb.append(String.join("\n\n", stmts)).append('\n');
    }

    // ------------------------------------------------------------------ data move script

    private int dataCount;

    private String dataScript() {
        String ss = opt.sourceSchema() == null ? "source_db" : opt.sourceSchema();
        String ts = opt.targetSchema() == null ? "target_db" : opt.targetSchema();
        StringBuilder sb = new StringBuilder();
        header(sb, "Data move: copies rows from the source schema into the refitted target schema.");
        sb.append("-- Run the refit script first. Both schemas must be on the same MySQL server: restore the source\n");
        sb.append("-- dump into a staging schema there if needed. Schemas used: source ").append(Sql.q(ss)).append(", target ").append(Sql.q(ts)).append(".\n");
        sb.append("-- Mode: ").append(switch (opt.dataMode()) {
            case "INSERT_IGNORE" -> "INSERT IGNORE (rows whose key already exists are skipped silently)";
            case "REPLACE" -> "REPLACE (rows whose key already exists are deleted and inserted again)";
            case "UPSERT" -> "INSERT ... ON DUPLICATE KEY UPDATE (existing rows take the source values)";
            default -> "INSERT (stops at the first row whose key already exists)";
        }).append(".\n");
        if (opt.sourceSchema() == null || opt.targetSchema() == null)
            sb.append("-- Replace source_db / target_db with your schema names, or enter them in Dry Dock and write the script again.\n");
        sb.append("\nSET @DRYDOCK_FK_CHECKS = @@FOREIGN_KEY_CHECKS;\nSET FOREIGN_KEY_CHECKS = 0;\n");

        List<String[]> pairs = new ArrayList<>();                       // {sourceKey, finalKey}
        Set<String> excluded = opt.excludeTables().stream().map(x -> x.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        for (Map.Entry<String, String> e : tableMap.entrySet()) {
            if (!fin.containsKey(e.getValue()) || !src.tables.containsKey(e.getKey())) continue;
            if (excluded.contains(e.getKey()) || excluded.contains(e.getValue())) continue;
            pairs.add(new String[]{e.getKey(), e.getValue()});
        }
        List<String[]> ordered = orderByForeignKeys(pairs);
        int notMovedCols = 0, placeholders = 0, risky = 0;
        int n = 0;
        for (String[] p : ordered) {
            Table s = src.tables.get(p[0]), f = fin.get(p[1]);
            Map<String, String> cm = colMap.getOrDefault(p[1], identity(f, s));
            List<String> cols = new ArrayList<>(), exprs = new ArrayList<>(), notes = new ArrayList<>();
            Set<String> used = new HashSet<>();
            for (Column fc : f.columns) {
                if (fc.generated != null) continue;
                String sName = cm.get(fc.name.toLowerCase(Locale.ROOT));
                Column sc = sName == null ? null : s.column(sName);
                if (sc == null) {
                    boolean needsValue = !fc.nullable && fc.defaultValue == null && !fc.autoIncrement;
                    if (!needsValue) continue;
                    cols.add(Sql.q(fc.name));
                    exprs.add(Types.placeholder(fc) + " AS " + Sql.q(fc.name));
                    notes.add("  -- REVIEW " + fc.name + ": no source column and NOT NULL without a default; filled with " + Types.placeholder(fc));
                    placeholders++;
                    continue;
                }
                used.add(sc.name.toLowerCase(Locale.ROOT));
                String expr = "s." + Sql.q(sc.name);
                Types.Verdict v = Types.compare(fc, sc, true);
                if (v == Types.Verdict.NARROWER) { notes.add("  -- CHECK " + fc.name + ": target " + fc.typeText() + " is narrower than source " + sc.typeText() + "; longer values are cut or rejected"); risky++; }
                else if (v == Types.Verdict.DIFFERENT) { notes.add("  -- CHECK " + fc.name + ": source " + sc.typeText() + " is converted to " + fc.typeText()); risky++; }
                if (sc.nullable && !fc.nullable) {
                    String fill = fc.defaultValue != null && !fc.defaultValue.toLowerCase(Locale.ROOT).contains("current_timestamp") && !fc.defaultValue.startsWith("(")
                            ? fc.defaultValue : Types.placeholder(fc);
                    expr = "COALESCE(" + expr + ", " + fill + ")";
                    notes.add("  -- CHECK " + fc.name + ": source allows NULL, target does not; NULL becomes " + fill);
                }
                if (!sc.name.equalsIgnoreCase(fc.name)) notes.add("  -- " + fc.name + " takes the values of source column " + sc.name);
                cols.add(Sql.q(fc.name));
                exprs.add(expr + " AS " + Sql.q(fc.name));
            }
            List<String> dropped = new ArrayList<>();
            for (Column sc : s.columns) if (!used.contains(sc.name.toLowerCase(Locale.ROOT)) && sc.generated == null) dropped.add(sc.name);
            notMovedCols += dropped.size();
            if (cols.isEmpty()) { warnings.add("Table " + f.name + ": no columns to move."); continue; }

            n++;
            sb.append("\n-- ").append(n).append(". ").append(s.name).append(s.name.equalsIgnoreCase(f.name) ? "" : " → " + f.name).append('\n');
            notes.forEach(x -> sb.append(x.substring(2)).append('\n'));
            if (!dropped.isEmpty()) sb.append("-- not moved (no column in the target): ").append(String.join(", ", dropped)).append('\n');
            if (opt.emptyTargetFirst()) sb.append("DELETE FROM ").append(Sql.q(ts, f.name)).append(";\n");
            String select = "SELECT " + String.join(",\n       ", exprs) + "\nFROM " + Sql.q(ss, s.name) + " AS s";
            switch (opt.dataMode()) {
                case "INSERT_IGNORE" -> sb.append("INSERT IGNORE INTO ");
                case "REPLACE" -> sb.append("REPLACE INTO ");
                default -> sb.append("INSERT INTO ");
            }
            sb.append(Sql.q(ts, f.name)).append(" (").append(String.join(", ", cols)).append(")\n");
            if (opt.dataMode().equals("UPSERT")) {
                sb.append("SELECT * FROM (\n").append(select.replaceAll("(?m)^", "  ")).append("\n) AS src_rows\nON DUPLICATE KEY UPDATE ");
                sb.append(cols.stream().map(c -> c + " = src_rows." + c).collect(Collectors.joining(", "))).append(";\n");
            } else {
                sb.append(select).append(";\n");
            }
        }
        dataCount = n;
        sb.append("\nSET FOREIGN_KEY_CHECKS = @DRYDOCK_FK_CHECKS;\n");
        if (n == 0) sb.append("\n-- No table has rows to move with the current choices.\n");
        if (placeholders > 0) warnings.add(placeholders + " target column(s) have no source column and are NOT NULL without a default; the data move fills them with a placeholder marked REVIEW.");
        if (risky > 0) warnings.add(risky + " column(s) may cut or convert values in the data move; each is marked CHECK in the script.");
        if (notMovedCols > 0) warnings.add(notMovedCols + " source column(s) have no column in the target and are not moved.");
        return sb.toString();
    }

    /** Parents before children, so the move also works with key checks on; cycles keep file order. */
    private List<String[]> orderByForeignKeys(List<String[]> pairs) {
        Map<String, String[]> byFinal = new LinkedHashMap<>();
        pairs.forEach(p -> byFinal.put(p[1], p));
        List<String[]> out = new ArrayList<>();
        Set<String> done = new HashSet<>(), visiting = new HashSet<>();
        for (String k : byFinal.keySet()) visit(k, byFinal, done, visiting, out);
        return out;
    }

    private void visit(String k, Map<String, String[]> byFinal, Set<String> done, Set<String> visiting, List<String[]> out) {
        if (done.contains(k) || !visiting.add(k)) return;
        Table t = fin.get(k);
        if (t != null)
            for (ForeignKey fk : t.foreignKeys.values()) {
                String ref = SchemaModel.key(fk.refTable);
                if (byFinal.containsKey(ref) && !ref.equals(k)) visit(ref, byFinal, done, visiting, out);
            }
        visiting.remove(k);
        if (done.add(k)) out.add(byFinal.get(k));
    }
}
