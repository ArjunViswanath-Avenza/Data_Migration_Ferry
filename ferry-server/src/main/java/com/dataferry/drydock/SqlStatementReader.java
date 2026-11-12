package com.dataferry.drydock;

import java.io.Closeable;
import java.io.IOException;
import java.io.Reader;
import java.util.Locale;
import java.util.Set;

/**
 * Splits a SQL script into statements, streaming, so a multi-gigabyte dump with data is fine.
 *
 * <ul>
 *   <li>Quotes, comments and {@code DELIMITER} lines are understood, so a ';' inside a string or a
 *       procedure body does not end a statement.</li>
 *   <li>MySQL "executable comments" ({@code /*!40101 ... *}{@code /}, {@code /*M!...}) are unwrapped,
 *       because mysqldump hides view and routine definitions inside them.</li>
 *   <li>Statements that cannot change a schema (INSERT, SET, LOCK, ...) are recognised from their
 *       first word and skipped without being kept in memory: only the scanner state is tracked.</li>
 * </ul>
 */
final class SqlStatementReader implements Closeable {

    /** First words of statements that never change a schema; their text is not kept. */
    private static final Set<String> SKIP = Set.of("INSERT", "REPLACE", "LOCK", "UNLOCK", "SET", "START", "COMMIT", "BEGIN",
            "ROLLBACK", "ANALYZE", "OPTIMIZE", "GRANT", "REVOKE", "FLUSH", "UPDATE", "DELETE", "SELECT", "CALL", "DO", "LOAD",
            "TRUNCATE", "SHOW", "EXPLAIN", "PREPARE", "EXECUTE", "DEALLOCATE", "SAVEPOINT", "RELEASE", "XA", "HANDLER", "VALUES");
    private static final int DECIDE_AT = 24;
    private static final int BUF = 1 << 16;

    private final Reader in;
    private final char[] buf = new char[BUF];
    private int pos, len;
    private boolean eof;

    private String delimiter = ";";
    private long bytesRead;
    private int kept, skipped;

    SqlStatementReader(Reader in) { this.in = in; }

    long charsRead() { return bytesRead; }
    int keptStatements() { return kept; }
    int skippedStatements() { return skipped; }

    // ------------------------------------------------------------------ buffer

    /** Character at offset from the current position, or -1 past the end. */
    private int peek(int offset) throws IOException {
        if (pos + offset >= len) fill(offset + 1);
        return pos + offset < len ? buf[pos + offset] : -1;
    }

    private void fill(int need) throws IOException {
        if (eof) return;
        if (pos > 0) {
            System.arraycopy(buf, pos, buf, 0, len - pos);
            len -= pos;
            pos = 0;
        }
        while (len < need && !eof) {
            int n = in.read(buf, len, buf.length - len);
            if (n < 0) eof = true;
            else { len += n; bytesRead += n; }
        }
    }

    private int read() throws IOException {
        int c = peek(0);
        if (c >= 0) pos++;
        return c;
    }

    private boolean startsWith(String s) throws IOException {
        for (int i = 0; i < s.length(); i++) {
            int c = peek(i);
            if (c < 0 || Character.toUpperCase((char) c) != Character.toUpperCase(s.charAt(i))) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ statements

    /** The next statement worth parsing, trimmed, without its delimiter; null at the end. */
    String next() throws IOException {
        StringBuilder sb = new StringBuilder(256);
        boolean skip = false;         // decided from the first word
        boolean decided = false;
        boolean inExec = false;       // inside an unwrapped /*! ... */
        int significant = 0;          // non-blank characters seen in this statement

        while (true) {
            int c = peek(0);
            if (c < 0) {
                String s = finish(sb, skip, decided);
                if (s == null && significant == 0) return null;
                return s == null ? next() : s;
            }

            // DELIMITER is a client command and only valid at the start of a statement.
            if (significant == 0 && (c == 'D' || c == 'd') && startsWith("DELIMITER") && isBlank(peek(9))) {
                pos += 9;
                String rest = readLine().trim();
                int sp = indexOfBlank(rest);
                delimiter = sp < 0 ? rest : rest.substring(0, sp);
                if (delimiter.isEmpty()) delimiter = ";";
                continue;
            }

            // End of statement.
            if (!inExec && c == delimiter.charAt(0) && startsWith(delimiter)) {
                pos += delimiter.length();
                if (significant == 0) continue;
                String s = finish(sb, skip, decided);
                if (s != null) return s;
                sb.setLength(0); skip = false; decided = false; significant = 0;
                continue;
            }

            if (c == '\'' || c == '"' || c == '`') {
                quoted((char) read(), skip ? null : sb);
                significant++;
            } else if (c == '-' && peek(1) == '-' && (isBlank(peek(2)) || peek(2) < 0)) {
                readLine();
                if (!skip) sb.append('\n');
            } else if (c == '#') {
                readLine();
                if (!skip) sb.append('\n');
            } else if (c == '/' && peek(1) == '*') {
                if (peek(2) == '!' || (peek(2) == 'M' && peek(3) == '!')) {
                    pos += peek(2) == '!' ? 3 : 4;
                    while (peek(0) >= '0' && peek(0) <= '9') pos++;
                    inExec = true;
                    if (!skip) sb.append(' ');
                } else {
                    pos += 2;
                    while (peek(0) >= 0 && !(peek(0) == '*' && peek(1) == '/')) pos++;
                    pos += 2;
                    if (!skip) sb.append(' ');
                }
            } else if (inExec && c == '*' && peek(1) == '/') {
                pos += 2;
                inExec = false;
                if (!skip) sb.append(' ');
            } else {
                read();
                if (!skip) sb.append((char) c);
                if (!isBlank(c)) significant++;
            }

            if (!decided && significant >= DECIDE_AT) {
                decided = true;
                if (SKIP.contains(firstWord(sb))) { skip = true; sb.setLength(0); }
            }
        }
    }

    private String finish(StringBuilder sb, boolean skip, boolean decided) {
        if (skip) { skipped++; return null; }
        String s = sb.toString().trim();
        if (s.isEmpty()) return null;
        if (!decided && SKIP.contains(firstWord(sb))) { skipped++; return null; }
        kept++;
        return s;
    }

    /** Copies a quoted string or identifier, honouring backslash escapes and doubled quotes. */
    private void quoted(char q, StringBuilder sb) throws IOException {
        if (sb != null) sb.append(q);
        while (true) {
            int c = read();
            if (c < 0) return;
            if (sb != null) sb.append((char) c);
            if (c == '\\' && q != '`') {
                int n = read();
                if (n < 0) return;
                if (sb != null) sb.append((char) n);
            } else if (c == q) {
                if (peek(0) == q) { read(); if (sb != null) sb.append(q); }
                else return;
            }
        }
    }

    private String readLine() throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = read()) >= 0 && c != '\n') sb.append((char) c);
        return sb.toString();
    }

    private static boolean isBlank(int c) { return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f'; }

    private static int indexOfBlank(String s) {
        for (int i = 0; i < s.length(); i++) if (isBlank(s.charAt(i))) return i;
        return -1;
    }

    static String firstWord(CharSequence s) {
        int i = 0, n = s.length();
        while (i < n && !Character.isLetter(s.charAt(i))) i++;
        int start = i;
        while (i < n && Character.isLetter(s.charAt(i))) i++;
        return s.subSequence(start, i).toString().toUpperCase(Locale.ROOT);
    }

    @Override
    public void close() throws IOException { in.close(); }
}
