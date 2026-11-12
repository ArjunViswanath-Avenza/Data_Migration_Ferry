package com.dataferry.drydock;

import com.dataferry.drydock.SchemaModel.*;

import java.util.List;
import java.util.stream.Collectors;

/** Writes MySQL DDL fragments from the schema model. Every name is back-quoted. */
final class Sql {

    private Sql() {}

    static String q(String name) { return "`" + name.replace("`", "``") + "`"; }

    static String q(String schema, String name) { return schema == null || schema.isBlank() ? q(name) : q(schema) + "." + q(name); }

    static String literal(String s) { return "'" + s.replace("\\", "\\\\").replace("'", "''") + "'"; }

    static String columnDefinition(Column c) {
        StringBuilder sb = new StringBuilder(q(c.name)).append(' ').append(c.typeText());
        if (c.charset != null) sb.append(" CHARACTER SET ").append(c.charset);
        if (c.collation != null) sb.append(" COLLATE ").append(c.collation);
        if (c.generated != null) {
            sb.append(" GENERATED ALWAYS AS ").append(c.generated.startsWith("(") ? c.generated : "(" + c.generated + ")");
            sb.append(' ').append(c.generatedKind == null ? "VIRTUAL" : c.generatedKind);
        }
        sb.append(c.nullable ? " NULL" : " NOT NULL");
        if (c.defaultValue != null && c.generated == null) sb.append(" DEFAULT ").append(c.defaultValue);
        if (c.onUpdate != null) sb.append(" ON UPDATE ").append(c.onUpdate);
        if (c.autoIncrement) sb.append(" AUTO_INCREMENT");
        if (c.comment != null && !c.comment.isEmpty()) sb.append(" COMMENT ").append(literal(c.comment));
        return sb.toString();
    }

    static String indexColumns(List<String> cols) {
        return cols.stream().map(Sql::indexColumn).collect(Collectors.joining(", "));
    }

    private static String indexColumn(String col) {
        if (col.startsWith("(")) return col;                       // functional key part
        String bare = DdlParser.bare(col);
        return q(bare) + col.substring(bare.length());
    }

    static String indexDefinition(Index ix) {
        String kind = ix.unique ? "UNIQUE KEY" : ix.kind != null ? ix.kind + " KEY" : "KEY";
        return kind + " " + q(ix.name) + " (" + indexColumns(ix.columns) + ")";
    }

    static String foreignKeyDefinition(ForeignKey fk) {
        StringBuilder sb = new StringBuilder("CONSTRAINT ").append(q(fk.name)).append(" FOREIGN KEY (")
                .append(fk.columns.stream().map(Sql::q).collect(Collectors.joining(", "))).append(") REFERENCES ")
                .append(q(fk.refTable)).append(" (").append(fk.refColumns.stream().map(Sql::q).collect(Collectors.joining(", "))).append(')');
        if (fk.onDelete != null) sb.append(" ON DELETE ").append(fk.onDelete);
        if (fk.onUpdate != null) sb.append(" ON UPDATE ").append(fk.onUpdate);
        return sb.toString();
    }

    static String createTable(Table t, boolean withForeignKeys) {
        StringBuilder sb = new StringBuilder("CREATE TABLE IF NOT EXISTS ").append(q(t.name)).append(" (\n");
        List<String> lines = new java.util.ArrayList<>();
        t.columns.forEach(c -> lines.add("  " + columnDefinition(c)));
        if (!t.primaryKey.isEmpty()) lines.add("  PRIMARY KEY (" + indexColumns(t.primaryKey) + ")");
        t.indexes.values().forEach(ix -> lines.add("  " + indexDefinition(ix)));
        if (withForeignKeys) t.foreignKeys.values().forEach(fk -> lines.add("  " + foreignKeyDefinition(fk)));
        sb.append(String.join(",\n", lines)).append("\n)");
        if (t.engine != null) sb.append(" ENGINE=").append(t.engine);
        if (t.charset != null) sb.append(" DEFAULT CHARSET=").append(t.charset);
        if (t.collation != null) sb.append(" COLLATE=").append(t.collation);
        if (t.comment != null && !t.comment.isEmpty()) sb.append(" COMMENT=").append(literal(t.comment));
        return sb.append(';').toString();
    }
}
