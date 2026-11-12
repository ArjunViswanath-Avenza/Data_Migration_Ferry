package com.dataferry.drydock;

import com.dataferry.drydock.SchemaModel.Column;

import java.math.BigInteger;
import java.util.*;

/**
 * MySQL column types: synonyms, normalisation, and whether one type can hold everything another
 * can ("wider"). The wider/narrower judgement is what lets Dry Dock say that varchar(50) → varchar(100)
 * is safe to carry over while the reverse would cut data.
 */
final class Types {

    enum Verdict { SAME, WIDER, NARROWER, DIFFERENT }

    private static final Map<String, String> SYNONYMS = Map.ofEntries(
            Map.entry("integer", "int"), Map.entry("int4", "int"), Map.entry("int1", "tinyint"), Map.entry("int2", "smallint"),
            Map.entry("int3", "mediumint"), Map.entry("middleint", "mediumint"), Map.entry("int8", "bigint"),
            Map.entry("dec", "decimal"), Map.entry("numeric", "decimal"), Map.entry("fixed", "decimal"),
            Map.entry("real", "double"), Map.entry("float8", "double"), Map.entry("float4", "float"),
            Map.entry("character", "char"), Map.entry("nchar", "char"), Map.entry("nvarchar", "varchar"),
            Map.entry("varcharacter", "varchar"), Map.entry("serial", "bigint"));

    private static final Map<String, Integer> INT_BITS = Map.of("tinyint", 8, "smallint", 16, "mediumint", 24, "int", 32, "bigint", 64);
    private static final Map<String, Long> TEXT_CAP = Map.of("tinytext", 255L, "text", 65_535L, "mediumtext", 16_777_215L, "longtext", 4_294_967_295L);
    private static final Map<String, Long> BLOB_CAP = Map.of("tinyblob", 255L, "blob", 65_535L, "mediumblob", 16_777_215L, "longblob", 4_294_967_295L);
    private static final Set<String> TEMPORAL = Set.of("datetime", "timestamp", "time");

    private Types() {}

    static void setType(Column col, String type, String args) {
        String t = SYNONYMS.getOrDefault(type, type);
        if (t.equals("bool") || t.equals("boolean")) { t = "tinyint"; args = "1"; }
        if (type.equals("serial")) col.unsigned = true;
        if (t.equals("decimal")) {
            if (args == null || args.isBlank()) args = "10,0";
            else if (!args.contains(",")) args = args.trim() + ",0";
        }
        if (t.equals("year") && "4".equals(args)) args = null;
        if (TEMPORAL.contains(t) && "0".equals(args)) args = null;
        if ((t.equals("char") || t.equals("binary") || t.equals("bit")) && (args == null || args.isBlank())) args = "1";
        if (args != null) args = args.replaceAll("\\s*,\\s*", ",").trim();
        col.dataType = t;
        col.args = args;
    }

    static boolean isInteger(String t) { return INT_BITS.containsKey(t); }

    static boolean isText(Column c) {
        String t = c.dataType;
        return t.equals("char") || t.equals("varchar") || TEXT_CAP.containsKey(t) || t.equals("enum") || t.equals("set");
    }

    static String charset(String v) {
        if (v == null) return null;
        String s = v.toLowerCase(Locale.ROOT);
        return s.equals("utf8") ? "utf8mb3" : s;
    }

    static String collation(String v) {
        if (v == null) return null;
        String s = v.toLowerCase(Locale.ROOT);
        return s.startsWith("utf8_") ? "utf8mb3_" + s.substring(5) : s;
    }

    /** The type for equality checks; integer display widths (int(11) vs int) are dropped when asked. */
    static String typeKey(Column c, boolean ignoreIntWidth) {
        boolean dropArgs = ignoreIntWidth && isInteger(c.dataType);
        return c.dataType + (dropArgs || c.args == null ? "" : "(" + c.args.toLowerCase(Locale.ROOT) + ")")
                + (c.unsigned ? " unsigned" : "") + (c.zerofill && !ignoreIntWidth ? " zerofill" : "");
    }

    /** How the source type relates to the target type. */
    static Verdict compare(Column source, Column target, boolean ignoreIntWidth) {
        if (typeKey(source, ignoreIntWidth).equals(typeKey(target, ignoreIntWidth))) return Verdict.SAME;
        String s = source.dataType, t = target.dataType;

        if (isInteger(s) && isInteger(t)) return range(intMin(source), intMax(source), intMin(target), intMax(target));

        if (s.equals("decimal") && t.equals("decimal")) {
            int[] a = precision(source.args), b = precision(target.args);
            if (a == null || b == null) return Verdict.DIFFERENT;
            int ai = a[0] - a[1], bi = b[0] - b[1];
            if (source.unsigned != target.unsigned) return Verdict.DIFFERENT;
            if (ai >= bi && a[1] >= b[1]) return Verdict.WIDER;
            if (ai <= bi && a[1] <= b[1]) return Verdict.NARROWER;
            return Verdict.DIFFERENT;
        }
        if (isInteger(s) && t.equals("decimal") || s.equals("decimal") && isInteger(t)) {
            // an integer fits a decimal with enough integer digits
            Column dec = s.equals("decimal") ? source : target, in = s.equals("decimal") ? target : source;
            int[] p = precision(dec.args);
            if (p == null) return Verdict.DIFFERENT;
            int digits = intMax(in).toString().length();
            boolean decHoldsInt = p[0] - p[1] >= digits;
            if (!decHoldsInt) return Verdict.DIFFERENT;
            return s.equals("decimal") ? Verdict.WIDER : Verdict.NARROWER;
        }
        if ((s.equals("float") || s.equals("double")) && (t.equals("float") || t.equals("double"))) {
            if (s.equals(t)) return Verdict.DIFFERENT;
            return s.equals("double") ? Verdict.WIDER : Verdict.NARROWER;
        }

        Long sc = textCapacity(source), tc = textCapacity(target);
        if (sc != null && tc != null) return capacity(sc, tc, s, t);
        sc = binaryCapacity(source); tc = binaryCapacity(target);
        if (sc != null && tc != null) return capacity(sc, tc, s, t);

        if (s.equals(t) && (s.equals("enum") || s.equals("set"))) {
            Set<String> a = values(source.args), b = values(target.args);
            if (a.containsAll(b)) return Verdict.WIDER;
            if (b.containsAll(a)) return Verdict.NARROWER;
            return Verdict.DIFFERENT;
        }
        if (s.equals(t) && TEMPORAL.contains(s)) {
            int a = fsp(source.args), b = fsp(target.args);
            return a > b ? Verdict.WIDER : a < b ? Verdict.NARROWER : Verdict.SAME;
        }
        if (s.equals("bit") && t.equals("bit")) return capacity(num(source.args), num(target.args), s, t);
        return Verdict.DIFFERENT;
    }

    private static Verdict capacity(long s, long t, String sType, String tType) {
        if (s > t) return Verdict.WIDER;
        if (s < t) return Verdict.NARROWER;
        return sType.equals(tType) ? Verdict.SAME : Verdict.DIFFERENT;   // char(10) vs varchar(10)
    }

    private static Verdict range(BigInteger sMin, BigInteger sMax, BigInteger tMin, BigInteger tMax) {
        boolean holds = sMin.compareTo(tMin) <= 0 && sMax.compareTo(tMax) >= 0;
        boolean held = sMin.compareTo(tMin) >= 0 && sMax.compareTo(tMax) <= 0;
        if (holds && held) return Verdict.SAME;
        if (holds) return Verdict.WIDER;
        if (held) return Verdict.NARROWER;
        return Verdict.DIFFERENT;
    }

    private static BigInteger intMin(Column c) {
        return c.unsigned ? BigInteger.ZERO : BigInteger.TWO.pow(INT_BITS.get(c.dataType) - 1).negate();
    }

    private static BigInteger intMax(Column c) {
        int bits = INT_BITS.get(c.dataType);
        return (c.unsigned ? BigInteger.TWO.pow(bits) : BigInteger.TWO.pow(bits - 1)).subtract(BigInteger.ONE);
    }

    private static Long textCapacity(Column c) {
        if (c.dataType.equals("char") || c.dataType.equals("varchar")) return num(c.args);
        return TEXT_CAP.get(c.dataType);
    }

    private static Long binaryCapacity(Column c) {
        if (c.dataType.equals("binary") || c.dataType.equals("varbinary")) return num(c.args);
        return BLOB_CAP.get(c.dataType);
    }

    private static long num(String args) {
        try { return Long.parseLong(args == null ? "0" : args.split(",")[0].trim()); }
        catch (NumberFormatException e) { return 0; }
    }

    private static int[] precision(String args) {
        try {
            String[] p = args.split(",");
            return new int[]{Integer.parseInt(p[0].trim()), p.length > 1 ? Integer.parseInt(p[1].trim()) : 0};
        } catch (RuntimeException e) { return null; }
    }

    private static int fsp(String args) {
        try { return args == null ? 0 : Integer.parseInt(args.trim()); }
        catch (NumberFormatException e) { return 0; }
    }

    static Set<String> values(String args) {
        Set<String> out = new LinkedHashSet<>();
        if (args == null) return out;
        for (DdlParser.Tok t : DdlParser.Tok.lex(args)) if (t.type == DdlParser.Tok.STRING) out.add(t.value());
        return out;
    }

    /** A literal that satisfies a NOT NULL column when the source has nothing to give it. */
    static String placeholder(Column c) {
        String t = c.dataType;
        if (isInteger(t) || t.equals("decimal") || t.equals("float") || t.equals("double") || t.equals("bit")) return "0";
        if (t.equals("date")) return "'1970-01-01'";
        if (t.equals("datetime") || t.equals("timestamp")) return "'1970-01-01 00:00:01'";
        if (t.equals("time")) return "'00:00:00'";
        if (t.equals("year")) return "1970";
        if (t.equals("enum") || t.equals("set")) {
            Iterator<String> it = values(c.args).iterator();
            return it.hasNext() ? "'" + it.next().replace("'", "''") + "'" : "''";
        }
        if (t.equals("json")) return "'{}'";
        return "''";
    }
}
