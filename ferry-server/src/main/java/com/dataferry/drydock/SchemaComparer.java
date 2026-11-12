package com.dataferry.drydock;

import com.dataferry.drydock.SchemaModel.*;
import com.dataferry.drydock.Types.Verdict;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.math.BigDecimal;
import java.util.*;

/**
 * Compares a SOURCE schema (the one whose changes should travel, e.g. a bank's customised database)
 * with a TARGET schema (the one being brought up to date, e.g. the new product release).
 *
 * Every difference gets the actions that make sense for it and a default that never loses data:
 * things only the source has are added, wider source types are carried over, things only the target
 * has are kept, and anything ambiguous (a different default, a changed index) is left as it is and
 * marked for review. Likely renames are paired by definition and name similarity.
 */
final class SchemaComparer {

    /** What to treat as noise. */
    public record Options(boolean ignoreIntDisplayWidth, boolean ignoreColumnOrder, boolean ignoreComments,
                          boolean ignoreCollations, boolean ignoreRowFormat, boolean detectRenames) {
        public static Options defaults() { return new Options(true, true, false, false, true, true); }
    }

    // ------------------------------------------------------------------ result shapes

    public static final class Comparison {
        public String id, createdAt;
        public Options options;
        public Side source, target;
        public List<Difference> differences = new ArrayList<>();
        public List<TablePair> tables = new ArrayList<>();
        public Map<String, Integer> bySeverity = new LinkedHashMap<>(), byArea = new LinkedHashMap<>();
        @JsonIgnore public SchemaModel sourceSchema, targetSchema;
    }

    public record Side(String fileName, String database, int tables, int columns, long views, long procedures, long functions,
                       long triggers, int statementsRead, int statementsSkipped, int notUnderstood, long characters, List<String> warnings) {
        static Side of(SchemaModel s) {
            return new Side(s.fileName, s.database, s.tables.size(), s.columnCount(), s.routineCount("VIEW"), s.routineCount("PROCEDURE"),
                    s.routineCount("FUNCTION"), s.routineCount("TRIGGER"), s.statementsRead, s.statementsSkipped,
                    s.statementsNotUnderstood, s.characters, s.warnings);
        }
    }

    /** One table as both sides see it. */
    public static final class TablePair {
        public String key, sourceName, targetName, status;       // SAME, CHANGED, ONLY_IN_SOURCE, ONLY_IN_TARGET, RENAMED
        public int differences, sourceColumns, targetColumns;
    }

    public static final class Difference {
        public String id;
        public String area;          // TABLE, COLUMN, PRIMARY_KEY, INDEX, FOREIGN_KEY, TABLE_OPTIONS, VIEW, PROCEDURE, FUNCTION, TRIGGER, EVENT
        public String kind;          // ONLY_IN_SOURCE, ONLY_IN_TARGET, CHANGED, RENAMED, NAME_ONLY
        public String tableKey;      // TablePair.key
        public String table;         // display name (target name when the target has it)
        public String object;        // display name of the column / index / routine
        public String sourceName, targetName;
        public String source, target;        // definitions, as SQL-like text
        public List<Aspect> aspects = new ArrayList<>();
        public String severity;      // INFO, SAFE, REVIEW, RISK
        public List<String> actions = new ArrayList<>();
        public String defaultAction;
        public String summary;       // plain words
        public String confidence;    // renames only: HIGH or MEDIUM
    }

    public record Aspect(String key, String label, String source, String target, String verdict, boolean applyByDefault, boolean cosmetic) {}

    // ------------------------------------------------------------------ compare

    private final SchemaModel src, tgt;
    private final Options o;
    private final Comparison out = new Comparison();

    private SchemaComparer(SchemaModel src, SchemaModel tgt, Options o) { this.src = src; this.tgt = tgt; this.o = o; }

    static Comparison compare(SchemaModel source, SchemaModel target, Options options) {
        return new SchemaComparer(source, target, options == null ? Options.defaults() : options).run();
    }

    private Comparison run() {
        out.options = o;
        out.source = Side.of(src);
        out.target = Side.of(tgt);
        out.sourceSchema = src;
        out.targetSchema = tgt;

        List<String> srcOnly = new ArrayList<>(), tgtOnly = new ArrayList<>();
        for (String k : src.tables.keySet()) if (!tgt.tables.containsKey(k)) srcOnly.add(k);
        for (String k : tgt.tables.keySet()) if (!src.tables.containsKey(k)) tgtOnly.add(k);

        // table renames first, so their columns are compared too
        Map<String, String> renamed = new LinkedHashMap<>();      // source key -> target key
        Map<String, String> renameConfidence = new HashMap<>();
        if (o.detectRenames()) {
            for (Pairing p : pair(srcOnly, tgtOnly, (a, b) -> tableSimilarity(src.tables.get(a), tgt.tables.get(b)))) {
                renamed.put(p.a, p.b);
                renameConfidence.put(p.a, p.score >= 0.85 ? "HIGH" : "MEDIUM");
            }
            srcOnly.removeAll(renamed.keySet());
            tgtOnly.removeAll(renamed.values());
        }

        // tables in both, in target order, then renamed pairs
        for (String k : tgt.tables.keySet()) if (src.tables.containsKey(k)) compareTables(k, src.tables.get(k), tgt.tables.get(k), null, null);
        renamed.forEach((sk, tk) -> compareTables(tk, src.tables.get(sk), tgt.tables.get(tk), renameConfidence.get(sk), sk));

        for (String k : srcOnly) {
            Table t = src.tables.get(k);
            Difference d = diff("TABLE", "ONLY_IN_SOURCE", "T:" + k, k, t.name, t.name);
            d.sourceName = t.name;
            d.source = t.columns.size() + " columns" + (t.primaryKey.isEmpty() ? "" : ", key (" + String.join(", ", t.primaryKey) + ")");
            d.severity = "SAFE";
            actions(d, "CREATE", "CREATE", "SKIP");
            d.summary = "Only the source has this table. Default: create it in the target.";
            addPair(k, t.name, null, "ONLY_IN_SOURCE", t.columns.size(), 0);
        }
        for (String k : tgtOnly) {
            Table t = tgt.tables.get(k);
            Difference d = diff("TABLE", "ONLY_IN_TARGET", "T:" + k, k, t.name, t.name);
            d.targetName = t.name;
            d.target = t.columns.size() + " columns";
            d.severity = "INFO";
            actions(d, "KEEP", "KEEP", "DROP");
            d.summary = "Only the target has this table (new in the target, or removed from the source). Default: keep it.";
            addPair(k, null, t.name, "ONLY_IN_TARGET", 0, t.columns.size());
        }

        compareRoutines();

        for (TablePair p : out.tables)
            p.differences = (int) out.differences.stream().filter(d -> p.key.equals(d.tableKey)).count();
        for (TablePair p : out.tables)
            if (p.status.equals("SAME") && p.differences > 0) p.status = "CHANGED";
        for (Difference d : out.differences) {
            out.bySeverity.merge(d.severity, 1, Integer::sum);
            out.byArea.merge(d.area, 1, Integer::sum);
        }
        return out;
    }

    private void addPair(String key, String s, String t, String status, int sc, int tc) {
        TablePair p = new TablePair();
        p.key = key; p.sourceName = s; p.targetName = t; p.status = status; p.sourceColumns = sc; p.targetColumns = tc;
        out.tables.add(p);
    }

    // ------------------------------------------------------------------ one table pair

    private void compareTables(String key, Table s, Table t, String renameConfidence, String sourceKey) {
        addPair(key, s.name, t.name, renameConfidence == null ? "SAME" : "RENAMED", s.columns.size(), t.columns.size());
        if (renameConfidence != null) {
            Difference d = diff("TABLE", "RENAMED", "TR:" + sourceKey + ">" + key, key, t.name, s.name + " → " + t.name);
            d.sourceName = s.name; d.targetName = t.name;
            d.source = s.name; d.target = t.name;
            d.confidence = renameConfidence;
            d.severity = "REVIEW";
            actions(d, renameConfidence.equals("HIGH") ? "MAP" : "SEPARATE", "MAP", "RENAME", "SEPARATE");
            d.summary = "Looks like the same table under another name (" + Math.round(100 * jaccard(names(s), names(t)))
                    + "% of columns shared). MAP keeps the target name and moves the source rows into it; RENAME renames the target "
                    + "table to the source name; SEPARATE treats them as different tables.";
        }

        Map<String, String> colRename = new LinkedHashMap<>();   // source column (lower) -> target column (lower), high confidence
        List<String> sOnly = new ArrayList<>(), tOnly = new ArrayList<>();
        for (Column c : s.columns) if (t.column(c.name) == null) sOnly.add(c.name);
        for (Column c : t.columns) if (s.column(c.name) == null) tOnly.add(c.name);

        if (o.detectRenames()) {
            for (Pairing p : pair(sOnly, tOnly, (a, b) -> columnSimilarity(s, s.column(a), t, t.column(b)))) {
                Column sc = s.column(p.a), tc = t.column(p.b);
                String conf = p.score >= 0.8 ? "HIGH" : "MEDIUM";
                if (conf.equals("HIGH")) colRename.put(p.a.toLowerCase(Locale.ROOT), p.b.toLowerCase(Locale.ROOT));
                Difference d = diff("COLUMN", "RENAMED", "CR:" + key + ":" + p.a.toLowerCase(Locale.ROOT) + ">" + p.b.toLowerCase(Locale.ROOT),
                        key, t.name, p.a + " → " + p.b);
                d.sourceName = sc.name; d.targetName = tc.name;
                d.source = Sql.columnDefinition(sc); d.target = Sql.columnDefinition(tc);
                d.confidence = conf;
                d.severity = conf.equals("HIGH") ? "REVIEW" : "RISK";
                actions(d, conf.equals("HIGH") ? "MAP" : "SEPARATE", "MAP", "RENAME", "SEPARATE");
                d.summary = "Probably the same column renamed. MAP keeps the target name and moves source values into it; "
                        + "RENAME gives the target column the source name; SEPARATE adds the source column and keeps both.";
                List<Aspect> asp = columnAspects(s, sc, t, tc);
                d.aspects.addAll(asp);
                sOnly.remove(p.a);
                tOnly.remove(p.b);
            }
        }

        for (String name : sOnly) {
            Column c = s.column(name);
            Difference d = diff("COLUMN", "ONLY_IN_SOURCE", "C:" + key + ":" + name.toLowerCase(Locale.ROOT), key, t.name, c.name);
            d.sourceName = c.name;
            d.source = Sql.columnDefinition(c);
            boolean needsValue = !c.nullable && c.defaultValue == null && !c.autoIncrement && c.generated == null;
            d.severity = needsValue ? "REVIEW" : "SAFE";
            actions(d, "ADD", "ADD", "SKIP");
            d.summary = "Only the source has this column. Default: add it to the target"
                    + (needsValue ? " (it is NOT NULL with no default, so existing target rows get the type's empty value)." : ".");
        }
        for (String name : tOnly) {
            Column c = t.column(name);
            Difference d = diff("COLUMN", "ONLY_IN_TARGET", "C:" + key + ":" + name.toLowerCase(Locale.ROOT), key, t.name, c.name);
            d.targetName = c.name;
            d.target = Sql.columnDefinition(c);
            boolean needsValue = !c.nullable && c.defaultValue == null && !c.autoIncrement && c.generated == null;
            d.severity = needsValue ? "REVIEW" : "INFO";
            actions(d, "KEEP", "KEEP", "DROP");
            d.summary = "Only the target has this column. Default: keep it."
                    + (needsValue ? " It is NOT NULL with no default, so the data move fills it with a placeholder you should check." : "");
        }

        for (Column sc : s.columns) {
            Column tc = t.column(sc.name);
            if (tc == null) continue;
            List<Aspect> asp = columnAspects(s, sc, t, tc);
            if (asp.isEmpty()) continue;
            Difference d = diff("COLUMN", "CHANGED", "C:" + key + ":" + sc.name.toLowerCase(Locale.ROOT), key, t.name, tc.name);
            d.sourceName = sc.name; d.targetName = tc.name;
            d.source = Sql.columnDefinition(sc); d.target = Sql.columnDefinition(tc);
            d.aspects.addAll(asp);
            boolean apply = asp.stream().anyMatch(Aspect::applyByDefault);
            boolean allCosmetic = asp.stream().allMatch(Aspect::cosmetic);
            boolean risky = asp.stream().anyMatch(a -> a.key().equals("type") && a.verdict().equals("DIFFERENT"));
            boolean review = asp.stream().anyMatch(a -> !a.applyByDefault() && !a.cosmetic() && !a.verdict().equals("NARROWER")
                    && !a.verdict().equals("STRICTER"));
            // SAFE = there is something to carry over and it cannot hurt; INFO = the target already covers it.
            d.severity = risky ? "RISK" : allCosmetic ? "INFO" : review ? "REVIEW" : apply ? "SAFE" : "INFO";
            actions(d, apply ? "APPLY" : "KEEP", "APPLY", "KEEP");
            d.summary = String.join(" ", asp.stream().map(SchemaComparer::say).toList());
        }

        // keys and indexes, with source column names translated through confident renames
        List<String> spk = translate(s.primaryKey, colRename), tpk = lower(t.primaryKey);
        if (!spk.equals(tpk)) {
            Difference d = diff("PRIMARY_KEY", spk.isEmpty() ? "ONLY_IN_TARGET" : tpk.isEmpty() ? "ONLY_IN_SOURCE" : "CHANGED",
                    "P:" + key, key, t.name, "PRIMARY KEY");
            d.source = s.primaryKey.isEmpty() ? null : "PRIMARY KEY (" + String.join(", ", s.primaryKey) + ")";
            d.target = t.primaryKey.isEmpty() ? null : "PRIMARY KEY (" + String.join(", ", t.primaryKey) + ")";
            d.severity = "REVIEW";
            if (spk.isEmpty()) { actions(d, "KEEP", "KEEP", "DROP"); d.summary = "Only the target has a primary key. Default: keep it."; }
            else { actions(d, "KEEP", "REPLACE", "KEEP"); d.summary = "The primary keys differ. Default: keep the target's; REPLACE uses the source's (fails if target rows would clash)."; }
        }
        compareIndexes(key, s, t, colRename);
        compareForeignKeys(key, s, t, colRename);
        compareTableOptions(key, s, t);
    }

    private List<Aspect> columnAspects(Table st, Column s, Table tt, Column t) {
        List<Aspect> a = new ArrayList<>();
        Verdict v = Types.compare(s, t, o.ignoreIntDisplayWidth());
        if (v != Verdict.SAME)
            a.add(new Aspect("type", "Type", s.typeText(), t.typeText(), v.name(), v == Verdict.WIDER, false));
        if (s.nullable != t.nullable)
            a.add(new Aspect("nullable", "Null allowed", s.nullable ? "NULL" : "NOT NULL", t.nullable ? "NULL" : "NOT NULL",
                    s.nullable ? "RELAXED" : "STRICTER", s.nullable, false));
        if (!sameDefault(s.defaultValue, t.defaultValue))
            a.add(new Aspect("default", "Default", show(s.defaultValue), show(t.defaultValue), "DIFFERENT", false, false));
        if (!sameDefault(s.onUpdate, t.onUpdate))
            a.add(new Aspect("onUpdate", "On update", show(s.onUpdate), show(t.onUpdate), "DIFFERENT", false, false));
        if (s.autoIncrement != t.autoIncrement)
            a.add(new Aspect("autoIncrement", "Auto increment", s.autoIncrement ? "yes" : "no", t.autoIncrement ? "yes" : "no", "DIFFERENT", false, false));
        // Columns that simply inherit their table's character set are covered by the table-options difference,
        // so a release that moves every table to utf8mb4 does not flag every text column.
        boolean explicit = s.charset != null || t.charset != null || s.collation != null || t.collation != null;
        if (Types.isText(s) && Types.isText(t) && explicit) {
            String sc = effCharset(st, s), tc = effCharset(tt, t);
            if (!Objects.equals(sc, tc)) {
                boolean wider = "utf8mb4".equals(sc) && !"utf8mb4".equals(tc);
                a.add(new Aspect("charset", "Character set", show(sc), show(tc), wider ? "WIDER" : "DIFFERENT", wider, false));
            }
            String sl = effCollation(st, s), tl = effCollation(tt, t);
            if (!o.ignoreCollations() && !Objects.equals(sl, tl) && Objects.equals(sc, tc))
                a.add(new Aspect("collation", "Collation", show(sl), show(tl), "DIFFERENT", false, false));
        }
        if (!o.ignoreComments() && !Objects.equals(blank(s.comment), blank(t.comment)))
            a.add(new Aspect("comment", "Comment", show(s.comment), show(t.comment), "DIFFERENT", false, true));
        if (!Objects.equals(blank(s.generated), blank(t.generated)) || (s.generated != null && !Objects.equals(s.generatedKind, t.generatedKind)))
            a.add(new Aspect("generated", "Generated as", show(s.generated), show(t.generated), "DIFFERENT", false, false));
        if (!o.ignoreColumnOrder()) {
            int si = st.indexOf(s.name), ti = tt.indexOf(t.name);
            String sp = si <= 0 ? "FIRST" : "after " + st.columns.get(si - 1).name, tp = ti <= 0 ? "FIRST" : "after " + tt.columns.get(ti - 1).name;
            if (!sp.equalsIgnoreCase(tp)) a.add(new Aspect("position", "Position", sp, tp, "DIFFERENT", false, true));
        }
        return a;
    }

    private static String say(Aspect a) {
        return switch (a.key()) {
            case "type" -> switch (a.verdict()) {
                case "WIDER" -> "Source type is wider (" + a.source() + " vs " + a.target() + "): carry it over so no value is cut.";
                case "NARROWER" -> "Target type is already wider (" + a.target() + " vs " + a.source() + "): keep it.";
                default -> "The type is different (" + a.source() + " vs " + a.target() + "): check values convert.";
            };
            case "nullable" -> a.verdict().equals("RELAXED") ? "Source allows NULL, target does not: relax the target so source rows load."
                    : "Target allows NULL, source does not: keep the target.";
            case "charset" -> "Character set " + a.source() + " in source, " + a.target() + " in target.";
            default -> a.label() + " differs: " + a.source() + " vs " + a.target() + ".";
        };
    }

    private String effCharset(Table t, Column c) { return c.charset != null ? c.charset : t.charset; }

    private String effCollation(Table t, Column c) {
        if (c.collation != null) return c.collation;
        return c.charset != null && !Objects.equals(c.charset, t.charset) ? null : t.collation;
    }

    // ------------------------------------------------------------------ indexes and foreign keys

    private void compareIndexes(String key, Table s, Table t, Map<String, String> rename) {
        Map<String, Index> sIdx = new LinkedHashMap<>(s.indexes), tIdx = new LinkedHashMap<>(t.indexes);
        // same definition, different name: nothing to move, mention only
        for (Iterator<Map.Entry<String, Index>> it = sIdx.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Index> e = it.next();
            String sig = signature(e.getValue(), rename);
            if (tIdx.containsKey(e.getKey()) && sig.equals(signature(tIdx.get(e.getKey()), Map.of()))) {
                tIdx.remove(e.getKey()); it.remove(); continue;
            }
            Optional<Map.Entry<String, Index>> twin = tIdx.entrySet().stream()
                    .filter(x -> signature(x.getValue(), Map.of()).equals(sig) && !sIdx.containsKey(x.getKey())).findFirst();
            if (twin.isPresent()) {
                Difference d = diff("INDEX", "NAME_ONLY", "I:" + key + ":" + e.getKey(), key, t.name, e.getValue().name + " → " + twin.get().getValue().name);
                d.sourceName = e.getValue().name; d.targetName = twin.get().getValue().name;
                d.source = Sql.indexDefinition(e.getValue()); d.target = Sql.indexDefinition(twin.get().getValue());
                d.severity = "INFO";
                actions(d, "KEEP", "KEEP");
                d.summary = "Same index, different name. Nothing to move.";
                tIdx.remove(twin.get().getKey()); it.remove();
            }
        }
        for (Map.Entry<String, Index> e : sIdx.entrySet()) {
            Index si = e.getValue(), ti = tIdx.remove(e.getKey());
            Difference d = diff("INDEX", ti == null ? "ONLY_IN_SOURCE" : "CHANGED", "I:" + key + ":" + e.getKey(), key, t.name, si.name);
            d.sourceName = si.name; d.source = Sql.indexDefinition(si);
            if (ti == null) {
                d.severity = si.unique ? "REVIEW" : "SAFE";
                actions(d, "ADD", "ADD", "SKIP");
                d.summary = "Only the source has this " + (si.unique ? "unique " : "") + "index. Default: add it."
                        + (si.unique ? " Adding fails if target rows already repeat these values." : "");
            } else {
                d.targetName = ti.name; d.target = Sql.indexDefinition(ti);
                d.severity = "REVIEW";
                actions(d, "KEEP", "REPLACE", "KEEP");
                d.summary = "The index has the same name but different columns or rules. Default: keep the target's.";
            }
        }
        for (Index ti : tIdx.values()) {
            Difference d = diff("INDEX", "ONLY_IN_TARGET", "I:" + key + ":" + SchemaModel.key(ti.name), key, t.name, ti.name);
            d.targetName = ti.name; d.target = Sql.indexDefinition(ti);
            d.severity = "INFO";
            actions(d, "KEEP", "KEEP", "DROP");
            d.summary = "Only the target has this index. Default: keep it.";
        }
    }

    private String signature(Index ix, Map<String, String> rename) {
        Index c = ix.copy();
        c.columns.replaceAll(x -> {
            String b = DdlParser.bare(x).toLowerCase(Locale.ROOT);
            return rename.getOrDefault(b, b) + x.substring(DdlParser.bare(x).length()).toLowerCase(Locale.ROOT);
        });
        return c.signature();
    }

    private void compareForeignKeys(String key, Table s, Table t, Map<String, String> rename) {
        Map<String, ForeignKey> tFk = new LinkedHashMap<>(t.foreignKeys);
        for (ForeignKey sf : s.foreignKeys.values()) {
            ForeignKey tr = sf.copy();
            tr.columns = translate(sf.columns, rename);
            String sig = tr.signature();
            Optional<String> same = tFk.keySet().stream().filter(k -> tFk.get(k).signature().equals(sig)).findFirst();
            if (same.isPresent()) { tFk.remove(same.get()); continue; }              // same rule; names may differ, that is fine
            ForeignKey tf = tFk.remove(SchemaModel.key(sf.name));
            Difference d = diff("FOREIGN_KEY", tf == null ? "ONLY_IN_SOURCE" : "CHANGED", "F:" + key + ":" + SchemaModel.key(sf.name), key, t.name, sf.name);
            d.sourceName = sf.name; d.source = Sql.foreignKeyDefinition(sf);
            if (tf == null) {
                d.severity = "SAFE";
                actions(d, "ADD", "ADD", "SKIP");
                d.summary = "Only the source has this foreign key. Default: add it (the script turns key checks off while it runs).";
            } else {
                d.targetName = tf.name; d.target = Sql.foreignKeyDefinition(tf);
                d.severity = "REVIEW";
                actions(d, "KEEP", "REPLACE", "KEEP");
                d.summary = "Same foreign key name, different rule. Default: keep the target's.";
            }
        }
        for (ForeignKey tf : tFk.values()) {
            Difference d = diff("FOREIGN_KEY", "ONLY_IN_TARGET", "F:" + key + ":" + SchemaModel.key(tf.name), key, t.name, tf.name);
            d.targetName = tf.name; d.target = Sql.foreignKeyDefinition(tf);
            d.severity = "INFO";
            actions(d, "KEEP", "KEEP", "DROP");
            d.summary = "Only the target has this foreign key. Default: keep it. The data move runs with key checks off.";
        }
    }

    private void compareTableOptions(String key, Table s, Table t) {
        List<Aspect> a = new ArrayList<>();
        if (!eqi(s.engine, t.engine)) a.add(new Aspect("engine", "Engine", show(s.engine), show(t.engine), "DIFFERENT", false, false));
        if (!Objects.equals(s.charset, t.charset)) a.add(new Aspect("charset", "Default character set", show(s.charset), show(t.charset), "DIFFERENT", false, false));
        if (!o.ignoreCollations() && !Objects.equals(s.collation, t.collation) && Objects.equals(s.charset, t.charset))
            a.add(new Aspect("collation", "Default collation", show(s.collation), show(t.collation), "DIFFERENT", false, false));
        if (!o.ignoreComments() && !Objects.equals(blank(s.comment), blank(t.comment)))
            a.add(new Aspect("comment", "Comment", show(s.comment), show(t.comment), "DIFFERENT", false, true));
        if (!o.ignoreRowFormat() && !eqi(s.rowFormat, t.rowFormat))
            a.add(new Aspect("rowFormat", "Row format", show(s.rowFormat), show(t.rowFormat), "DIFFERENT", false, true));
        if (a.isEmpty()) return;
        Difference d = diff("TABLE_OPTIONS", "CHANGED", "O:" + key, key, t.name, "table options");
        d.aspects.addAll(a);
        // A target already on utf8mb4 holds anything the source can: nothing to decide, so it is information only.
        boolean onlyCharset = a.stream().allMatch(x -> x.cosmetic() || x.key().equals("charset") || x.key().equals("collation"));
        d.severity = a.stream().allMatch(Aspect::cosmetic) || (onlyCharset && "utf8mb4".equals(t.charset)) ? "INFO" : "REVIEW";
        actions(d, "KEEP", "APPLY", "KEEP");
        d.summary = String.join(" ", a.stream().map(x -> x.label() + ": " + x.source() + " vs " + x.target() + ".").toList())
                + " Default: keep the target's (a new default character set applies to new columns only).";
    }

    // ------------------------------------------------------------------ views, routines, triggers

    private void compareRoutines() {
        for (Routine r : src.routines.values()) {
            Routine t = tgt.routines.get(r.key());
            if (t != null && t.normalized.equals(r.normalized)) continue;
            Difference d = diff(r.kind, t == null ? "ONLY_IN_SOURCE" : "CHANGED", "R:" + r.key().toLowerCase(Locale.ROOT),
                    r.table == null ? null : SchemaModel.key(r.table), r.table, r.name);
            d.sourceName = r.name; d.source = r.sql;
            if (t == null) {
                d.severity = "SAFE";
                actions(d, "CREATE", "CREATE", "SKIP");
                d.summary = "Only the source has this " + r.kind.toLowerCase(Locale.ROOT) + ". Default: create it in the target.";
            } else {
                d.targetName = t.name; d.target = t.sql;
                d.severity = "REVIEW";
                actions(d, "KEEP", "REPLACE", "KEEP");
                d.summary = "The " + r.kind.toLowerCase(Locale.ROOT) + " is written differently. Default: keep the target's; REPLACE uses the source's.";
            }
        }
        for (Routine t : tgt.routines.values()) {
            if (src.routines.containsKey(t.key())) continue;
            Difference d = diff(t.kind, "ONLY_IN_TARGET", "R:" + t.key().toLowerCase(Locale.ROOT),
                    t.table == null ? null : SchemaModel.key(t.table), t.table, t.name);
            d.targetName = t.name; d.target = t.sql;
            d.severity = "INFO";
            actions(d, "KEEP", "KEEP", "DROP");
            d.summary = "Only the target has this " + t.kind.toLowerCase(Locale.ROOT) + ". Default: keep it.";
        }
    }

    // ------------------------------------------------------------------ similarity

    private record Pairing(String a, String b, double score) {}

    private interface Scorer { double score(String a, String b); }

    /** Greedy best-first pairing of two leftover lists; scores below 0.6 are not paired. */
    private static List<Pairing> pair(List<String> as, List<String> bs, Scorer scorer) {
        List<Pairing> all = new ArrayList<>();
        if ((long) as.size() * bs.size() > 250_000) return all;            // too many to guess sensibly
        for (String a : as) for (String b : bs) {
            double s = scorer.score(a, b);
            if (s >= 0.6) all.add(new Pairing(a, b, s));
        }
        all.sort(Comparator.comparingDouble(Pairing::score).reversed());
        Set<String> usedA = new HashSet<>(), usedB = new HashSet<>();
        List<Pairing> out = new ArrayList<>();
        for (Pairing p : all) {
            if (usedA.contains(p.a) || usedB.contains(p.b)) continue;
            usedA.add(p.a);
            usedB.add(p.b);
            out.add(p);
        }
        return out;
    }

    private double tableSimilarity(Table a, Table b) {
        double j = jaccard(names(a), names(b));
        double n = nameSimilarity(a.name, b.name);
        if (j < 0.6) return 0;
        if (j < 0.9 && n < 0.4) return 0;
        return 0.75 * j + 0.25 * n;
    }

    private double columnSimilarity(Table st, Column a, Table tt, Column b) {
        boolean sameDef = Types.compare(a, b, true) == Verdict.SAME && a.nullable == b.nullable && sameDefault(a.defaultValue, b.defaultValue);
        boolean sameFamily = Types.compare(a, b, true) != Verdict.DIFFERENT;
        double n = nameSimilarity(a.name, b.name);
        int ai = st.indexOf(a.name), bi = tt.indexOf(b.name);
        boolean sameNeighbour = ai > 0 && bi > 0 && st.columns.get(ai - 1).name.equalsIgnoreCase(tt.columns.get(bi - 1).name);
        if (!sameFamily) return 0;
        double score = (sameDef ? 0.45 : 0.2) + 0.45 * n + (sameNeighbour ? 0.1 : 0);
        if (n < 0.3 && !(sameDef && sameNeighbour)) return 0;
        return score;
    }

    private static Set<String> names(Table t) {
        Set<String> s = new HashSet<>();
        t.columns.forEach(c -> s.add(c.name.toLowerCase(Locale.ROOT)));
        return s;
    }

    static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) return 1;
        Set<String> i = new HashSet<>(a); i.retainAll(b);
        Set<String> u = new HashSet<>(a); u.addAll(b);
        return (double) i.size() / u.size();
    }

    /** 1 = same name, 0 = nothing in common; ignores case and underscores. */
    static double nameSimilarity(String a, String b) {
        String x = a.toLowerCase(Locale.ROOT).replace("_", ""), y = b.toLowerCase(Locale.ROOT).replace("_", "");
        if (x.equals(y)) return 1;
        int max = Math.max(x.length(), y.length());
        if (max == 0) return 1;
        double lev = 1 - (double) levenshtein(x, y) / max;
        boolean contains = x.contains(y) || y.contains(x);
        return contains ? Math.max(lev, 0.7) : lev;
    }

    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++)
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }

    // ------------------------------------------------------------------ helpers

    private Difference diff(String area, String kind, String id, String tableKey, String table, String object) {
        Difference d = new Difference();
        d.id = id; d.area = area; d.kind = kind; d.tableKey = tableKey; d.table = table; d.object = object;
        out.differences.add(d);
        return d;
    }

    private static void actions(Difference d, String def, String... allowed) {
        d.actions = List.of(allowed);
        d.defaultAction = def;
    }

    private static List<String> translate(List<String> cols, Map<String, String> rename) {
        List<String> out = new ArrayList<>();
        for (String c : cols) { String l = c.toLowerCase(Locale.ROOT); out.add(rename.getOrDefault(l, l)); }
        return out;
    }

    private static List<String> lower(List<String> cols) { return cols.stream().map(c -> c.toLowerCase(Locale.ROOT)).toList(); }

    static boolean sameDefault(String a, String b) {
        String x = normDefault(a), y = normDefault(b);
        if (Objects.equals(x, y)) return true;
        if (x == null || y == null) return false;
        try { return new BigDecimal(x).compareTo(new BigDecimal(y)) == 0; }
        catch (NumberFormatException e) { return false; }
    }

    private static String normDefault(String v) {
        if (v == null) return null;
        String s = v.trim();
        while (s.startsWith("(") && s.endsWith(")") && s.length() > 2) s = s.substring(1, s.length() - 1).trim();
        if (s.equalsIgnoreCase("null")) return null;
        if (s.startsWith("'") || s.startsWith("\"")) return DdlParser.Tok.unquote(s);
        String l = s.toLowerCase(Locale.ROOT).replace(" ", "");
        if (l.matches("(current_timestamp|now|localtimestamp|localtime)(\\(\\))?")) return "current_timestamp";
        if (l.matches("(current_timestamp|now|localtimestamp|localtime)\\((\\d)\\)")) return "current_timestamp(" + l.replaceAll("\\D", "") + ")";
        return l;
    }

    private static boolean eqi(String a, String b) { return a == null ? b == null : a.equalsIgnoreCase(b); }
    private static String blank(String s) { return s == null || s.isBlank() ? null : s; }
    private static String show(String s) { return s == null ? "none" : s; }
}
