package com.dataferry.drydock;

import java.util.*;

/**
 * A database schema as read from a SQL script: tables (columns, keys, indexes, foreign keys,
 * options), views, routines and triggers. Names are matched case-insensitively, as MySQL does on
 * Windows and as Temenos databases are usually configured; the original spelling is kept for output.
 */
public final class SchemaModel {

    public String fileName;
    public String database;                         // from CREATE DATABASE / USE, if any
    public final Set<String> databasesSeen = new LinkedHashSet<>();
    public final Map<String, Table> tables = new LinkedHashMap<>();        // key: lower-case name
    public final Map<String, Routine> routines = new LinkedHashMap<>();    // key: KIND:lower-case name
    public final List<String> warnings = new ArrayList<>();
    public int statementsRead, statementsSkipped, statementsNotUnderstood;
    public long characters;

    public static String key(String name) { return name == null ? null : name.toLowerCase(Locale.ROOT); }

    public Table table(String name) { return tables.get(key(name)); }

    public void warn(String message) { if (warnings.size() < 200) warnings.add(message); }

    public int columnCount() { return tables.values().stream().mapToInt(t -> t.columns.size()).sum(); }

    public long routineCount(String kind) { return routines.values().stream().filter(r -> r.kind.equals(kind)).count(); }

    // ------------------------------------------------------------------ tables

    public static final class Table {
        public String name;
        public final List<Column> columns = new ArrayList<>();
        public List<String> primaryKey = new ArrayList<>();
        public final Map<String, Index> indexes = new LinkedHashMap<>();       // key: lower-case name
        public final Map<String, ForeignKey> foreignKeys = new LinkedHashMap<>();
        public String engine, charset, collation, comment, rowFormat;

        public Table(String name) { this.name = name; }

        public Column column(String name) {
            for (Column c : columns) if (c.name.equalsIgnoreCase(name)) return c;
            return null;
        }

        public int indexOf(String column) {
            for (int i = 0; i < columns.size(); i++) if (columns.get(i).name.equalsIgnoreCase(column)) return i;
            return -1;
        }

        public Table copy(String newName) {
            Table t = new Table(newName);
            columns.forEach(c -> t.columns.add(c.copy()));
            t.primaryKey = new ArrayList<>(primaryKey);
            indexes.forEach((k, v) -> t.indexes.put(k, v.copy()));
            foreignKeys.forEach((k, v) -> t.foreignKeys.put(k, v.copy()));
            t.engine = engine; t.charset = charset; t.collation = collation; t.comment = comment; t.rowFormat = rowFormat;
            return t;
        }
    }

    public static final class Column {
        public String name;
        public String dataType;            // lower case, synonyms resolved: int, varchar, decimal, ...
        public String args;                // inside the brackets, as written: "50", "10,2", "'A','B'"
        public boolean unsigned, zerofill;
        public String charset, collation;  // as written on the column; null = table default
        public boolean nullable = true;
        public String defaultValue;        // as written, e.g. 'abc', 0, CURRENT_TIMESTAMP, (uuid()); null = none
        public String onUpdate;
        public boolean autoIncrement;
        public String comment;
        public String generated;           // expression, for generated columns
        public String generatedKind;       // VIRTUAL or STORED

        public Column copy() {
            Column c = new Column();
            c.name = name; c.dataType = dataType; c.args = args; c.unsigned = unsigned; c.zerofill = zerofill;
            c.charset = charset; c.collation = collation; c.nullable = nullable; c.defaultValue = defaultValue;
            c.onUpdate = onUpdate; c.autoIncrement = autoIncrement; c.comment = comment; c.generated = generated;
            c.generatedKind = generatedKind;
            return c;
        }

        /** The type as MySQL would show it: varchar(50), decimal(10,2) unsigned. */
        public String typeText() {
            StringBuilder sb = new StringBuilder(dataType);
            if (args != null && !args.isBlank()) sb.append('(').append(args).append(')');
            if (unsigned) sb.append(" unsigned");
            if (zerofill) sb.append(" zerofill");
            return sb.toString();
        }
    }

    public static final class Index {
        public String name;
        public boolean unique;
        public String kind;                 // null (BTREE), FULLTEXT or SPATIAL
        public List<String> columns = new ArrayList<>();   // as written, with prefix length: name(20)

        public Index copy() {
            Index i = new Index();
            i.name = name; i.unique = unique; i.kind = kind; i.columns = new ArrayList<>(columns);
            return i;
        }

        public String signature() {
            return (unique ? "U:" : "") + (kind == null ? "" : kind + ":") + String.join(",", columns).toLowerCase(Locale.ROOT);
        }
    }

    public static final class ForeignKey {
        public String name;
        public List<String> columns = new ArrayList<>();
        public String refTable;
        public List<String> refColumns = new ArrayList<>();
        public String onDelete, onUpdate;   // null = RESTRICT / NO ACTION

        public ForeignKey copy() {
            ForeignKey f = new ForeignKey();
            f.name = name; f.columns = new ArrayList<>(columns); f.refTable = refTable;
            f.refColumns = new ArrayList<>(refColumns); f.onDelete = onDelete; f.onUpdate = onUpdate;
            return f;
        }

        public String signature() {
            return (String.join(",", columns) + "->" + refTable + "(" + String.join(",", refColumns) + ")"
                    + " D:" + norm(onDelete) + " U:" + norm(onUpdate)).toLowerCase(Locale.ROOT);
        }

        private static String norm(String action) {
            return action == null || action.equalsIgnoreCase("NO ACTION") ? "RESTRICT" : action.toUpperCase(Locale.ROOT);
        }
    }

    // ------------------------------------------------------------------ views, routines, triggers

    public static final class Routine {
        public String kind;          // VIEW, PROCEDURE, FUNCTION, TRIGGER, EVENT
        public String name;
        public String table;         // triggers only
        public String sql;           // the CREATE statement without DEFINER, ready to run
        public String normalized;    // for comparison: lower case, single spaces, no backticks

        public String key() { return kind + ":" + SchemaModel.key(name); }
    }
}
