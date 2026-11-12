package com.dataferry.drydock;

import com.dataferry.drydock.SchemaModel.*;

import java.io.IOException;
import java.io.Reader;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds a {@link SchemaModel} by replaying a SQL script's DDL in order: CREATE / ALTER / DROP /
 * RENAME for tables, indexes, views, routines and triggers. Replaying (rather than only reading
 * CREATE TABLE) means a base schema followed by upgrade scripts gives the end state, and a
 * mysqldump's view stand-in tables disappear when the real view is created.
 *
 * MySQL / MariaDB syntax. A statement that cannot be understood is counted and named in the
 * warnings; it never stops the rest of the file.
 */
final class DdlParser {

    private static final Pattern DEFINER = Pattern.compile(
            "DEFINER\\s*=\\s*(?:CURRENT_USER(?:\\s*\\(\\s*\\))?|(?:`[^`]*`|'[^']*'|\"[^\"]*\"|[\\w.$%-]+)\\s*@\\s*(?:`[^`]*`|'[^']*'|\"[^\"]*\"|[\\w.$%-]+))",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ALGORITHM = Pattern.compile("ALGORITHM\\s*=\\s*\\w+", Pattern.CASE_INSENSITIVE);
    private static final Pattern SECURITY = Pattern.compile("SQL\\s+SECURITY\\s+\\w+", Pattern.CASE_INSENSITIVE);

    private final SchemaModel schema;

    private DdlParser(SchemaModel schema) { this.schema = schema; }

    static SchemaModel parse(Reader reader, String fileName) throws IOException {
        SchemaModel schema = new SchemaModel();
        schema.fileName = fileName;
        DdlParser parser = new DdlParser(schema);
        try (SqlStatementReader statements = new SqlStatementReader(reader)) {
            String sql;
            while ((sql = statements.next()) != null) parser.apply(sql);
            schema.statementsRead = statements.keptStatements() + statements.skippedStatements();
            schema.statementsSkipped = statements.skippedStatements();
            schema.characters = statements.charsRead();
        }
        parser.finish();
        return schema;
    }

    /** Parses a script held in memory (tests, small pastes). */
    static SchemaModel parse(String sql, String fileName) {
        try { return parse(new java.io.StringReader(sql), fileName); }
        catch (IOException e) { throw new IllegalStateException(e); }
    }

    // ================================================================== statements

    void apply(String sql) {
        if (sql.indexOf('﻿') >= 0) sql = sql.replace("﻿", "");     // byte-order mark at the top of a file
        List<Tok> toks;
        try { toks = Tok.lex(sql); }
        catch (RuntimeException e) { notUnderstood(sql, "could not be split into words"); return; }
        if (toks.isEmpty()) return;
        Cursor c = new Cursor(sql, toks);
        try {
            String first = c.upper();
            switch (first) {
                case "CREATE" -> create(c);
                case "ALTER" -> {
                    c.next();
                    while (c.acceptWord("ONLINE", "OFFLINE", "IGNORE")) { /* modifiers */ }
                    if (c.acceptWord("TABLE")) alterTable(c);
                }
                case "DROP" -> drop(c);
                case "RENAME" -> renameTables(c);
                case "USE" -> { c.next(); useDatabase(c.name()); }
                default -> { /* other statements cannot change the schema */ }
            }
        } catch (RuntimeException e) {
            notUnderstood(sql, e.getMessage());
        }
    }

    private void notUnderstood(String sql, String why) {
        schema.statementsNotUnderstood++;
        String head = sql.replaceAll("\\s+", " ");
        schema.warn("Not understood (" + why + "): " + (head.length() > 110 ? head.substring(0, 110) + "…" : head));
    }

    private void useDatabase(String name) {
        schema.databasesSeen.add(name);
        if (schema.database == null) schema.database = name;
    }

    private void create(Cursor c) {
        c.next();                                              // CREATE
        c.acceptWords("OR", "REPLACE");
        boolean temporary = c.acceptWord("TEMPORARY");
        String uniqueKind = null;
        while (true) {
            if (c.acceptWord("ALGORITHM")) { c.acceptSym('='); c.next(); continue; }
            if (c.acceptWord("DEFINER")) { c.acceptSym('='); skipUser(c); continue; }
            if (c.acceptWords("SQL", "SECURITY")) { c.next(); continue; }
            if (c.acceptWord("UNIQUE")) { uniqueKind = "UNIQUE"; continue; }
            if (c.acceptWord("FULLTEXT")) { uniqueKind = "FULLTEXT"; continue; }
            if (c.acceptWord("SPATIAL")) { uniqueKind = "SPATIAL"; continue; }
            if (c.acceptWord("ONLINE", "OFFLINE", "AGGREGATE")) continue;
            break;
        }
        String what = c.upper();
        switch (what) {
            case "TABLE" -> { c.next(); if (temporary) return; createTable(c); }
            case "VIEW", "PROCEDURE", "FUNCTION", "TRIGGER", "EVENT" -> { c.next(); createRoutine(c, what); }
            case "INDEX", "KEY" -> { c.next(); createIndex(c, uniqueKind); }
            case "DATABASE", "SCHEMA" -> { c.next(); c.acceptWords("IF", "NOT", "EXISTS"); useDatabase(c.name()); }
            default -> { /* users, roles, servers, tablespaces: not part of a schema comparison */ }
        }
    }

    private static void skipUser(Cursor c) {
        if (c.acceptWord("CURRENT_USER")) { if (c.isSym('(')) c.group(); return; }
        c.next();
        if (c.acceptSym('@')) c.next();
    }

    // ------------------------------------------------------------------ CREATE TABLE

    private void createTable(Cursor c) {
        c.acceptWords("IF", "NOT", "EXISTS");
        String name = c.qualifiedName(schema);
        if (c.acceptWord("LIKE") || (c.isSym('(') && c.peekWord(1, "LIKE"))) {
            boolean paren = c.acceptSym('(');
            if (paren) c.next();
            String from = c.qualifiedName(schema);
            Table src = schema.table(from);
            if (src == null) { schema.warn("CREATE TABLE " + name + " LIKE " + from + ": " + from + " is not defined earlier in the file."); return; }
            schema.tables.put(SchemaModel.key(name), src.copy(name));
            return;
        }
        if (!c.isSym('(')) {
            schema.warn("CREATE TABLE " + name + " has no column list (CREATE ... AS SELECT); its columns are unknown and it is left out.");
            return;
        }
        Table t = new Table(name);
        for (List<Tok> def : c.groupItems()) tableElement(t, new Cursor(c.sql, def));
        tableOptions(t, c);
        // A view stand-in created by mysqldump is replaced here; a real duplicate is just redefined.
        schema.routines.remove("VIEW:" + SchemaModel.key(name));
        schema.tables.put(SchemaModel.key(name), t);
    }

    /** One element inside CREATE TABLE ( ... ): a column, key, index, foreign key or check. */
    private void tableElement(Table t, Cursor c) {
        if (c.atEnd()) return;
        String constraintName = null;
        if (c.acceptWord("CONSTRAINT")) {
            if (!c.isWord("PRIMARY", "UNIQUE", "FOREIGN", "CHECK")) constraintName = c.name();
        }
        if (c.acceptWords("PRIMARY", "KEY")) {
            skipIndexType(c);
            t.primaryKey = indexColumns(c);
        } else if (c.isWord("UNIQUE", "KEY", "INDEX", "FULLTEXT", "SPATIAL")) {
            addIndex(t, c, constraintName);
        } else if (c.acceptWords("FOREIGN", "KEY")) {
            ForeignKey fk = foreignKey(c, constraintName);
            if (fk.name == null) fk.name = t.name + "_ibfk_" + (t.foreignKeys.size() + 1);
            t.foreignKeys.put(SchemaModel.key(fk.name), fk);
        } else if (c.isWord("CHECK")) {
            // check constraints are not compared
        } else {
            Column col = column(c, t);
            int at = t.indexOf(col.name);
            if (at >= 0) t.columns.set(at, col); else t.columns.add(col);
        }
    }

    private void addIndex(Table t, Cursor c, String constraintName) {
        Index ix = new Index();
        if (c.acceptWord("UNIQUE")) ix.unique = true;
        else if (c.acceptWord("FULLTEXT")) ix.kind = "FULLTEXT";
        else if (c.acceptWord("SPATIAL")) ix.kind = "SPATIAL";
        c.acceptWord("KEY", "INDEX");
        if (!c.isSym('(') && !c.isWord("USING")) ix.name = c.name();
        skipIndexType(c);
        ix.columns = indexColumns(c);
        if (ix.name == null) ix.name = constraintName;
        if (ix.name == null) ix.name = ix.columns.isEmpty() ? "index_" + (t.indexes.size() + 1) : bare(ix.columns.get(0));
        t.indexes.put(SchemaModel.key(ix.name), ix);
    }

    private static void skipIndexType(Cursor c) {
        if (c.acceptWord("USING")) c.next();
    }

    private List<String> indexColumns(Cursor c) {
        List<String> cols = new ArrayList<>();
        if (!c.isSym('(')) return cols;
        for (List<Tok> item : c.groupItems()) {
            Cursor ic = new Cursor(c.sql, item);
            if (ic.isSym('(')) { cols.add(ic.groupText()); continue; }     // functional key part
            String name = ic.name();
            if (ic.isSym('(')) name += "(" + ic.groupInner().replace(" ", "") + ")";
            if (ic.acceptWord("DESC")) name += " DESC";
            cols.add(name);
        }
        skipIndexType(c);
        return cols;
    }

    private ForeignKey foreignKey(Cursor c, String constraintName) {
        ForeignKey fk = new ForeignKey();
        fk.name = constraintName;
        if (!c.isSym('(')) { String n = c.name(); if (fk.name == null) fk.name = n; }
        fk.columns = plainNames(c);
        if (c.acceptWord("REFERENCES")) {
            fk.refTable = c.qualifiedName(schema);
            fk.refColumns = plainNames(c);
            while (!c.atEnd()) {
                if (c.acceptWords("ON", "DELETE")) fk.onDelete = referenceAction(c);
                else if (c.acceptWords("ON", "UPDATE")) fk.onUpdate = referenceAction(c);
                else if (c.acceptWord("MATCH")) c.next();
                else break;
            }
        }
        return fk;
    }

    private static String referenceAction(Cursor c) {
        if (c.acceptWords("SET", "NULL")) return "SET NULL";
        if (c.acceptWords("SET", "DEFAULT")) return "SET DEFAULT";
        if (c.acceptWords("NO", "ACTION")) return "NO ACTION";
        return c.next().text.toUpperCase(Locale.ROOT);
    }

    private List<String> plainNames(Cursor c) {
        List<String> out = new ArrayList<>();
        if (!c.isSym('(')) return out;
        for (List<Tok> item : c.groupItems()) out.add(new Cursor(c.sql, item).name());
        return out;
    }

    private void tableOptions(Table t, Cursor c) {
        while (!c.atEnd()) {
            if (c.isWord("PARTITION", "AS", "SELECT", "IGNORE", "REPLACE")) break;
            if (c.acceptSym(',')) continue;
            if (c.acceptWords("DEFAULT", "CHARACTER", "SET") || c.acceptWords("CHARACTER", "SET")
                    || c.acceptWords("DEFAULT", "CHARSET") || c.acceptWord("CHARSET")) {
                c.acceptSym('='); t.charset = Types.charset(c.next().value());
            } else if (c.acceptWords("DEFAULT", "COLLATE") || c.acceptWord("COLLATE")) {
                c.acceptSym('='); t.collation = Types.collation(c.next().value());
            } else if (c.acceptWord("ENGINE", "TYPE")) {
                c.acceptSym('='); t.engine = c.next().value();
            } else if (c.acceptWord("COMMENT")) {
                c.acceptSym('='); t.comment = c.next().value();
            } else if (c.acceptWord("ROW_FORMAT")) {
                c.acceptSym('='); t.rowFormat = c.next().value().toUpperCase(Locale.ROOT);
            } else {
                c.next();                                 // AUTO_INCREMENT=, AVG_ROW_LENGTH=, STATS_* ...: not compared
                if (c.acceptSym('=')) { if (c.isSym('(')) c.group(); else c.next(); }
            }
        }
    }

    // ------------------------------------------------------------------ columns

    private Column column(Cursor c, Table t) {
        Column col = new Column();
        col.name = c.name();
        String type = c.next().text.toLowerCase(Locale.ROOT);
        // multi-word types
        if ((type.equals("double") && c.acceptWord("PRECISION"))) type = "double";
        if (type.equals("national")) type = c.next().text.toLowerCase(Locale.ROOT);
        if (type.equals("character") && c.acceptWord("VARYING")) type = "varchar";
        if (type.equals("long")) { type = c.acceptWord("VARBINARY") ? "mediumblob" : "mediumtext"; c.acceptWord("VARCHAR"); }
        if (type.equals("char") && c.acceptWord("VARYING")) type = "varchar";
        String args = c.isSym('(') ? c.groupInner() : null;
        Types.setType(col, type, args);
        if (type.equals("serial")) { col.nullable = false; col.autoIncrement = true; }

        while (!c.atEnd()) {
            if (c.acceptWord("UNSIGNED")) col.unsigned = true;
            else if (c.acceptWord("SIGNED")) col.unsigned = false;
            else if (c.acceptWord("ZEROFILL")) { col.zerofill = true; col.unsigned = true; }
            else if (c.acceptWords("CHARACTER", "SET") || c.acceptWord("CHARSET")) col.charset = Types.charset(c.next().value());
            else if (c.acceptWord("COLLATE")) col.collation = Types.collation(c.next().value());
            else if (c.acceptWord("BINARY")) { /* binary collation shorthand */ }
            else if (c.acceptWords("NOT", "NULL")) col.nullable = false;
            else if (c.acceptWord("NULL")) col.nullable = true;
            else if (c.acceptWord("DEFAULT")) col.defaultValue = defaultExpression(c);
            else if (c.acceptWords("ON", "UPDATE")) col.onUpdate = defaultExpression(c);
            else if (c.acceptWord("AUTO_INCREMENT")) col.autoIncrement = true;
            else if (c.acceptWord("COMMENT")) col.comment = c.next().value();
            else if (c.acceptWords("PRIMARY", "KEY") || c.acceptWord("KEY")) { t.primaryKey = new ArrayList<>(List.of(col.name)); col.nullable = false; }
            else if (c.acceptWord("UNIQUE")) {
                c.acceptWord("KEY");
                Index ix = new Index(); ix.name = col.name; ix.unique = true; ix.columns = new ArrayList<>(List.of(col.name));
                t.indexes.put(SchemaModel.key(ix.name), ix);
            }
            else if (c.acceptWords("GENERATED", "ALWAYS", "AS") || c.acceptWord("AS")) { col.generated = c.groupText(); col.generatedKind = "VIRTUAL"; }
            else if (c.acceptWord("VIRTUAL")) col.generatedKind = "VIRTUAL";
            else if (c.acceptWord("STORED", "PERSISTENT")) col.generatedKind = "STORED";
            else if (c.acceptWord("REFERENCES")) { c.qualifiedName(schema); if (c.isSym('(')) c.group(); }
            else if (c.acceptWord("CHECK", "CONSTRAINT")) { if (!c.isSym('(') && !c.atEnd()) c.next(); if (c.isSym('(')) c.group(); }
            else if (c.isSym('(')) c.group();
            else c.next();                                     // COLUMN_FORMAT, STORAGE, INVISIBLE, SRID ...: not compared
        }
        if (col.defaultValue != null && col.defaultValue.equalsIgnoreCase("NULL")) col.defaultValue = null;
        return col;
    }

    private static String defaultExpression(Cursor c) {
        if (c.isSym('(')) return c.groupText();
        Tok t = c.next();
        StringBuilder sb = new StringBuilder(c.sql.substring(t.start, t.end));
        if ((t.text.equals("-") || t.text.equals("+")) && !c.atEnd()) {
            Tok n = c.next();
            sb.append(c.sql, n.start, n.end);
        } else if (t.type == Tok.WORD && !c.atEnd() && c.peek().type == Tok.STRING && c.peek().start == t.end) {
            Tok s = c.next();                                    // b'0', x'FF', _utf8mb4'abc'
            sb.append(c.sql, s.start, s.end);
        } else if (t.type == Tok.WORD && c.isSym('(')) {
            sb.append(c.groupText());                            // CURRENT_TIMESTAMP(6), now()
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ ALTER TABLE

    private void alterTable(Cursor c) {
        String name = c.qualifiedName(schema);
        Table t = schema.table(name);
        if (t == null) { schema.warn("ALTER TABLE " + name + ": the table is not defined earlier in the file; change ignored."); return; }
        for (List<Tok> spec : c.restItems()) alterSpec(t, new Cursor(c.sql, spec));
    }

    private void alterSpec(Table t, Cursor c) {
        if (c.atEnd()) return;
        if (c.acceptWord("ADD")) {
            if (c.isWord("CONSTRAINT", "PRIMARY", "UNIQUE", "KEY", "INDEX", "FULLTEXT", "SPATIAL", "FOREIGN", "CHECK")) {
                tableElement(t, c);
                return;
            }
            c.acceptWord("COLUMN");
            if (c.isSym('(')) {
                for (List<Tok> def : c.groupItems()) tableElement(t, new Cursor(c.sql, def));
                return;
            }
            Cursor colCursor = c.until("FIRST", "AFTER");
            Column col = column(colCursor, t);
            t.columns.removeIf(x -> x.name.equalsIgnoreCase(col.name));
            place(t, col, c);
        } else if (c.acceptWord("MODIFY")) {
            c.acceptWord("COLUMN");
            Cursor colCursor = c.until("FIRST", "AFTER");
            Column col = column(colCursor, t);
            int at = t.indexOf(col.name);
            if (at < 0) { schema.warn("ALTER TABLE " + t.name + " MODIFY " + col.name + ": no such column."); return; }
            if (c.atEnd()) t.columns.set(at, col);
            else { t.columns.remove(at); place(t, col, c); }
        } else if (c.acceptWord("CHANGE")) {
            c.acceptWord("COLUMN");
            String old = c.name();
            Cursor colCursor = c.until("FIRST", "AFTER");
            Column col = column(colCursor, t);
            int at = t.indexOf(old);
            if (at < 0) { schema.warn("ALTER TABLE " + t.name + " CHANGE " + old + ": no such column."); return; }
            renameInKeys(t, old, col.name);
            if (c.atEnd()) t.columns.set(at, col);
            else { t.columns.remove(at); place(t, col, c); }
        } else if (c.acceptWord("DROP")) {
            if (c.acceptWords("PRIMARY", "KEY")) t.primaryKey = new ArrayList<>();
            else if (c.acceptWords("FOREIGN", "KEY")) t.foreignKeys.remove(SchemaModel.key(c.name()));
            else if (c.acceptWord("INDEX", "KEY")) t.indexes.remove(SchemaModel.key(c.name()));
            else if (c.acceptWord("CONSTRAINT", "CHECK")) {
                String n = SchemaModel.key(c.name());
                t.foreignKeys.remove(n); t.indexes.remove(n);
            } else {
                c.acceptWord("COLUMN");
                String col = c.name();
                t.columns.removeIf(x -> x.name.equalsIgnoreCase(col));
                t.primaryKey.removeIf(x -> x.equalsIgnoreCase(col));
                t.indexes.values().forEach(ix -> ix.columns.removeIf(x -> bare(x).equalsIgnoreCase(col)));
                t.indexes.values().removeIf(ix -> ix.columns.isEmpty());
            }
        } else if (c.acceptWord("RENAME")) {
            if (c.acceptWord("COLUMN")) {
                String from = c.name(); c.acceptWord("TO"); String to = c.name();
                Column col = t.column(from);
                if (col != null) { renameInKeys(t, from, to); col.name = to; }
            } else if (c.acceptWord("INDEX", "KEY")) {
                String from = c.name(); c.acceptWord("TO"); String to = c.name();
                Index ix = t.indexes.remove(SchemaModel.key(from));
                if (ix != null) { ix.name = to; t.indexes.put(SchemaModel.key(to), ix); }
            } else {
                if (!c.acceptWord("TO")) c.acceptWord("AS");
                renameTable(t.name, c.qualifiedName(schema));
            }
        } else if (c.acceptWord("ALTER")) {
            c.acceptWord("COLUMN");
            Column col = t.column(c.name());
            if (col == null) return;
            if (c.acceptWords("SET", "DEFAULT")) col.defaultValue = defaultExpression(c);
            else if (c.acceptWords("DROP", "DEFAULT")) col.defaultValue = null;
        } else if (c.acceptWord("CONVERT")) {
            c.acceptWord("TO");
            if (c.acceptWords("CHARACTER", "SET") || c.acceptWord("CHARSET")) {
                String cs = Types.charset(c.next().value());
                String coll = c.acceptWord("COLLATE") ? Types.collation(c.next().value()) : null;
                t.charset = cs; t.collation = coll;
                for (Column col : t.columns) if (Types.isText(col)) { col.charset = null; col.collation = null; }
            }
        } else {
            tableOptions(t, c);
        }
    }

    private static void place(Table t, Column col, Cursor c) {
        if (c.acceptWord("FIRST")) { t.columns.add(0, col); return; }
        if (c.acceptWord("AFTER")) {
            int at = t.indexOf(c.name());
            if (at >= 0) { t.columns.add(at + 1, col); return; }
        }
        t.columns.add(col);
    }

    private static void renameInKeys(Table t, String from, String to) {
        if (from.equalsIgnoreCase(to)) return;
        t.primaryKey.replaceAll(x -> x.equalsIgnoreCase(from) ? to : x);
        for (Index ix : t.indexes.values())
            ix.columns.replaceAll(x -> bare(x).equalsIgnoreCase(from) ? to + x.substring(bare(x).length()) : x);
        for (ForeignKey fk : t.foreignKeys.values()) fk.columns.replaceAll(x -> x.equalsIgnoreCase(from) ? to : x);
    }

    /** "name(20) DESC" → "name". */
    static String bare(String indexColumn) {
        int i = 0;
        while (i < indexColumn.length() && indexColumn.charAt(i) != '(' && indexColumn.charAt(i) != ' ') i++;
        return indexColumn.substring(0, i);
    }

    // ------------------------------------------------------------------ DROP, RENAME, CREATE INDEX

    private void drop(Cursor c) {
        c.next();
        c.acceptWord("TEMPORARY");
        String what = c.next().text.toUpperCase(Locale.ROOT);
        c.acceptWords("IF", "EXISTS");
        switch (what) {
            case "TABLE", "TABLES" -> {
                do schema.tables.remove(SchemaModel.key(c.qualifiedName(schema))); while (c.acceptSym(','));
            }
            case "VIEW", "PROCEDURE", "FUNCTION", "TRIGGER", "EVENT" -> {
                do schema.routines.remove(what + ":" + SchemaModel.key(c.qualifiedName(schema))); while (c.acceptSym(','));
            }
            case "INDEX", "KEY" -> {
                String ix = c.name();
                if (c.acceptWord("ON")) {
                    Table t = schema.table(c.qualifiedName(schema));
                    if (t != null) {
                        if (ix.equalsIgnoreCase("PRIMARY")) t.primaryKey = new ArrayList<>();
                        else t.indexes.remove(SchemaModel.key(ix));
                    }
                }
            }
            default -> { /* DROP DATABASE / USER ...: not part of the schema */ }
        }
    }

    private void renameTables(Cursor c) {
        c.next();
        if (!c.acceptWord("TABLE", "TABLES")) return;
        do {
            String from = c.qualifiedName(schema);
            c.acceptWord("TO");
            renameTable(from, c.qualifiedName(schema));
        } while (c.acceptSym(','));
    }

    private void renameTable(String from, String to) {
        Table t = schema.tables.remove(SchemaModel.key(from));
        if (t == null) return;
        t.name = to;
        schema.tables.put(SchemaModel.key(to), t);
    }

    private void createIndex(Cursor c, String kind) {
        Index ix = new Index();
        if ("UNIQUE".equals(kind)) ix.unique = true; else ix.kind = kind;
        c.acceptWords("IF", "NOT", "EXISTS");
        ix.name = c.name();
        skipIndexType(c);
        if (!c.acceptWord("ON")) return;
        Table t = schema.table(c.qualifiedName(schema));
        if (t == null) return;
        ix.columns = indexColumns(c);
        t.indexes.put(SchemaModel.key(ix.name), ix);
    }

    // ------------------------------------------------------------------ views, routines, triggers

    private void createRoutine(Cursor c, String kind) {
        c.acceptWords("IF", "NOT", "EXISTS");
        Routine r = new Routine();
        r.kind = kind;
        r.name = c.qualifiedName(schema);
        if (kind.equals("TRIGGER")) {
            while (!c.atEnd() && !c.isWord("ON")) c.next();
            if (c.acceptWord("ON")) r.table = c.qualifiedName(schema);
        }
        String text = DEFINER.matcher(c.sql).replaceFirst("");
        // Bodies are kept exactly as written (string literals inside them matter); only the head is tidied.
        r.sql = text.trim().replaceFirst("(?i)^CREATE\\s+(OR\\s+REPLACE\\s+)?", "CREATE ");
        if (kind.equals("VIEW")) {
            schema.tables.remove(SchemaModel.key(r.name));      // mysqldump's stand-in table
            r.sql = ALGORITHM.matcher(r.sql).replaceFirst("").replaceFirst("^CREATE\\s+", "CREATE ");
        }
        schema.routines.put(r.key(), r);
    }

    /** Normalised text for comparing routines: no definer, algorithm, security, quoting, schema prefix or spacing. */
    private void finish() {
        for (Routine r : schema.routines.values()) {
            String s = SECURITY.matcher(ALGORITHM.matcher(r.sql).replaceAll("")).replaceAll("");
            s = s.replace("`", "").replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
            s = s.replaceFirst("^create (or replace )?", "create ");
            for (String db : schema.databasesSeen) s = s.replace(db.toLowerCase(Locale.ROOT) + ".", "");
            r.normalized = s;
        }
        if (schema.databasesSeen.size() > 1)
            schema.warn("The file names " + schema.databasesSeen.size() + " databases (" + String.join(", ", schema.databasesSeen)
                    + "). Tables are compared by name only, so give one database per file for a clean comparison.");
        if (schema.tables.isEmpty())
            schema.warn("No CREATE TABLE statements were found. Use a schema export such as mysqldump --no-data --routines --triggers.");
    }

    // ================================================================== tokens

    /** One lexical token of a statement. */
    static final class Tok {
        static final int WORD = 0, QUOTED_ID = 1, STRING = 2, SYM = 3;
        final int type, start, end;
        final String text;

        Tok(int type, String text, int start, int end) { this.type = type; this.text = text; this.start = start; this.end = end; }

        /** Value without quotes: identifiers, string literals, option values. */
        String value() { return type == STRING ? unquote(text) : text; }

        static String unquote(String s) {
            if (s.length() < 2) return s;
            char q = s.charAt(0);
            String inner = s.substring(1, s.length() - 1);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < inner.length(); i++) {
                char ch = inner.charAt(i);
                if (ch == '\\' && q != '`' && i + 1 < inner.length()) {
                    char n = inner.charAt(++i);
                    sb.append(switch (n) { case 'n' -> '\n'; case 't' -> '\t'; case 'r' -> '\r'; case '0' -> '\0'; default -> n; });
                } else if (ch == q && i + 1 < inner.length() && inner.charAt(i + 1) == q) {
                    sb.append(q); i++;
                } else sb.append(ch);
            }
            return sb.toString();
        }

        static List<Tok> lex(String s) {
            List<Tok> out = new ArrayList<>();
            int i = 0, n = s.length();
            while (i < n) {
                char ch = s.charAt(i);
                if (Character.isWhitespace(ch)) { i++; continue; }
                int start = i;
                if (ch == '`') {
                    i = closeQuote(s, i, '`');
                    out.add(new Tok(QUOTED_ID, unquote(s.substring(start, i)), start, i));
                } else if (ch == '\'' || ch == '"') {
                    i = closeQuote(s, i, ch);
                    out.add(new Tok(STRING, s.substring(start, i), start, i));
                } else if (Character.isLetterOrDigit(ch) || ch == '_' || ch == '$' || ch > 127) {
                    while (i < n && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_' || s.charAt(i) == '$' || s.charAt(i) > 127
                            || (s.charAt(i) == '.' && i + 1 < n && Character.isDigit(s.charAt(i + 1)) && isNumber(s, start, i)))) i++;
                    out.add(new Tok(WORD, s.substring(start, i), start, i));
                } else {
                    i++;
                    out.add(new Tok(SYM, String.valueOf(ch), start, i));
                }
            }
            return out;
        }

        private static boolean isNumber(String s, int from, int to) {
            for (int k = from; k < to; k++) if (!Character.isDigit(s.charAt(k))) return false;
            return true;
        }

        private static int closeQuote(String s, int i, char q) {
            int n = s.length();
            i++;
            while (i < n) {
                char ch = s.charAt(i);
                if (ch == '\\' && q != '`') { i += 2; continue; }
                if (ch == q) {
                    if (i + 1 < n && s.charAt(i + 1) == q) { i += 2; continue; }
                    return i + 1;
                }
                i++;
            }
            return n;
        }
    }

    /** A read position over a token list, with the small helpers a DDL parser needs. */
    static final class Cursor {
        final String sql;
        final List<Tok> toks;
        int i;

        Cursor(String sql, List<Tok> toks) { this.sql = sql; this.toks = toks; }

        boolean atEnd() { return i >= toks.size(); }
        Tok peek() { return toks.get(i); }

        Tok next() {
            if (atEnd()) throw new IllegalArgumentException("statement ends too early");
            return toks.get(i++);
        }

        String upper() { return atEnd() ? "" : peek().type == Tok.WORD ? peek().text.toUpperCase(Locale.ROOT) : ""; }

        boolean isWord(String... words) {
            String u = upper();
            for (String w : words) if (u.equals(w)) return true;
            return false;
        }

        boolean peekWord(int offset, String word) {
            int k = i + offset;
            return k < toks.size() && toks.get(k).type == Tok.WORD && toks.get(k).text.equalsIgnoreCase(word);
        }

        boolean acceptWord(String... words) {
            if (isWord(words)) { i++; return true; }
            return false;
        }

        /** Accepts the whole sequence or nothing. */
        boolean acceptWords(String... seq) {
            for (int k = 0; k < seq.length; k++) if (!peekWord(k, seq[k])) return false;
            i += seq.length;
            return true;
        }

        boolean isSym(char ch) { return !atEnd() && peek().type == Tok.SYM && peek().text.charAt(0) == ch; }

        boolean acceptSym(char ch) {
            if (isSym(ch)) { i++; return true; }
            return false;
        }

        /** An identifier: bare word, `quoted`, or "quoted" / 'quoted'. */
        String name() {
            Tok t = next();
            if (t.type == Tok.SYM) throw new IllegalArgumentException("expected a name, found '" + t.text + "'");
            return t.value();
        }

        /** db.name or name; the qualifier is recorded as a database seen. */
        String qualifiedName(SchemaModel schema) {
            String n = name();
            if (acceptSym('.')) {
                schema.databasesSeen.add(n);
                if (schema.database == null) schema.database = n;
                n = name();
            }
            return n;
        }

        /** Skips a bracketed group starting at '(' and returns the index after it. */
        int group() {
            int depth = 0;
            do {
                Tok t = next();
                if (t.type == Tok.SYM && t.text.equals("(")) depth++;
                else if (t.type == Tok.SYM && t.text.equals(")")) depth--;
            } while (depth > 0 && !atEnd());
            return i;
        }

        /** The text of a bracketed group, brackets included. */
        String groupText() {
            int from = i;
            group();
            return sql.substring(toks.get(from).start, toks.get(i - 1).end);
        }

        /** The text inside a bracketed group, spacing normalised outside quotes. */
        String groupInner() {
            String g = groupText();
            return g.substring(1, g.length() - 1).trim().replaceAll("\\s*,\\s*", ",");
        }

        /** The comma-separated items of the bracketed group at the cursor. */
        List<List<Tok>> groupItems() {
            int from = i;
            int end = group();
            return split(toks.subList(from + 1, end - 1));
        }

        /** The comma-separated items from here to the end. */
        List<List<Tok>> restItems() {
            List<List<Tok>> out = split(toks.subList(i, toks.size()));
            i = toks.size();
            return out;
        }

        /** A cursor over the tokens up to (not including) the first top-level word given; this cursor moves past them. */
        Cursor until(String... words) {
            int from = i, depth = 0;
            while (!atEnd()) {
                Tok t = peek();
                if (t.type == Tok.SYM && t.text.equals("(")) depth++;
                else if (t.type == Tok.SYM && t.text.equals(")")) depth--;
                else if (depth == 0 && isWord(words)) break;
                i++;
            }
            return new Cursor(sql, toks.subList(from, i));
        }

        private static List<List<Tok>> split(List<Tok> list) {
            List<List<Tok>> out = new ArrayList<>();
            int depth = 0, start = 0;
            for (int k = 0; k < list.size(); k++) {
                Tok t = list.get(k);
                if (t.type != Tok.SYM) continue;
                if (t.text.equals("(")) depth++;
                else if (t.text.equals(")")) depth--;
                else if (t.text.equals(",") && depth == 0) { out.add(list.subList(start, k)); start = k + 1; }
            }
            if (start < list.size()) out.add(list.subList(start, list.size()));
            return out;
        }
    }

    static Matcher definer(String sql) { return DEFINER.matcher(sql); }
}
