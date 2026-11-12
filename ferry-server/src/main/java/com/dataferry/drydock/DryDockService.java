package com.dataferry.drydock;

import com.dataferry.core.FerryProperties;
import com.dataferry.drydock.SchemaComparer.Comparison;
import com.dataferry.drydock.SchemaComparer.Difference;
import com.dataferry.drydock.SchemaModel.Column;
import com.dataferry.drydock.SchemaModel.Table;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Dry Dock: compares two database schemas from SQL files and writes the scripts that carry the
 * chosen differences, and then the data, from the source to the target.
 *
 * Comparisons are kept in memory (the last {@value #KEEP}); the files themselves are read once,
 * streaming, and not stored. Nothing here connects to a database.
 */
@Service
public class DryDockService {

    private static final Logger log = LoggerFactory.getLogger(DryDockService.class);
    static final int KEEP = 8;

    private final Path samples;
    private final Map<String, Stored> comparisons = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Stored> e) { return size() > KEEP; }
    });

    private static final class Stored {
        final Comparison comparison;
        Map<String, ScriptWriter.Decision> decisions = Map.of();
        ScriptWriter.Result lastScripts;
        Stored(Comparison c) { comparison = c; }
    }

    public DryDockService(FerryProperties props) {
        this.samples = props.configPath().resolve("../samples/drydock").normalize();
    }

    // ------------------------------------------------------------------ compare

    /** An input file: its name and a way to open it. */
    public record Input(String fileName, Supplier<InputStream> open) {}

    public Comparison compare(Input source, Input target, SchemaComparer.Options options) {
        long t0 = System.currentTimeMillis();
        SchemaModel s = read(source), t = read(target);
        Comparison c = SchemaComparer.compare(s, t, options);
        c.id = "DD-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
        c.createdAt = Instant.now().toString();
        comparisons.put(c.id, new Stored(c));
        log.info("Dry Dock {}: {} ({} tables) vs {} ({} tables): {} differences in {} ms", c.id, s.fileName, s.tables.size(),
                t.fileName, t.tables.size(), c.differences.size(), System.currentTimeMillis() - t0);
        return c;
    }

    public Comparison compareSamples(SchemaComparer.Options options) {
        Path s = samples.resolve("source_customised.sql"), t = samples.resolve("target_new_release.sql");
        if (!Files.exists(s) || !Files.exists(t)) throw new NoSuchElementException("The sample files are not in " + samples);
        return compare(new Input(s.getFileName().toString(), () -> open(s)), new Input(t.getFileName().toString(), () -> open(t)), options);
    }

    private static InputStream open(Path p) {
        try { return Files.newInputStream(p); } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    static SchemaModel read(Input in) {
        String name = in.fileName() == null ? "schema.sql" : in.fileName();
        String lower = name.toLowerCase(Locale.ROOT);
        try (InputStream raw = new BufferedInputStream(in.open().get(), 1 << 16)) {
            InputStream body;
            if (lower.endsWith(".gz")) body = new GZIPInputStream(raw, 1 << 16);
            else if (lower.endsWith(".zip")) body = sqlEntries(new ZipInputStream(raw));
            else body = raw;
            Reader reader = new InputStreamReader(body, StandardCharsets.UTF_8);
            return DdlParser.parse(reader, name);
        } catch (IOException e) {
            throw new IllegalArgumentException(name + " could not be read: " + e.getMessage());
        }
    }

    /** Every .sql entry of a zip, one after another, with a statement break between files. */
    private static InputStream sqlEntries(ZipInputStream z) throws IOException {
        return new InputStream() {
            private boolean open = advance(), first = true;
            private byte[] gap = new byte[0];
            private int gapPos;

            private boolean advance() throws IOException {
                ZipEntry e;
                while ((e = z.getNextEntry()) != null)
                    if (!e.isDirectory() && e.getName().toLowerCase(Locale.ROOT).endsWith(".sql")) return true;
                return false;
            }

            @Override public int read() throws IOException {
                byte[] b = new byte[1];
                return read(b, 0, 1) < 0 ? -1 : b[0] & 0xff;
            }

            @Override public int read(byte[] b, int off, int len) throws IOException {
                if (gapPos < gap.length) { b[off] = gap[gapPos++]; return 1; }
                while (open) {
                    int n = z.read(b, off, len);
                    if (n >= 0) { first = false; return n; }
                    open = advance();
                    if (open) { gap = "\n;\nDELIMITER ;\n".getBytes(StandardCharsets.US_ASCII); gapPos = 0; return read(b, off, len); }
                }
                return -1;
            }

            @Override public void close() throws IOException { z.close(); }
        };
    }

    // ------------------------------------------------------------------ read back

    public record Listed(String id, String createdAt, String source, String target, int differences) {}

    public List<Listed> list() {
        List<Listed> out = new ArrayList<>();
        synchronized (comparisons) {
            for (Stored s : comparisons.values()) {
                Comparison c = s.comparison;
                out.add(new Listed(c.id, c.createdAt, c.source.fileName(), c.target.fileName(), c.differences.size()));
            }
        }
        Collections.reverse(out);
        return out;
    }

    public Comparison get(String id) { return stored(id).comparison; }

    private Stored stored(String id) {
        Stored s = comparisons.get(id);
        if (s == null) throw new NoSuchElementException("Comparison " + id + " is not in memory any more (the server keeps the last "
                + KEEP + " and forgets them on restart). Compare the files again.");
        return s;
    }

    /** Both sides of one table, for the side-by-side view. */
    public Map<String, Object> table(String id, String key) {
        Comparison c = get(id);
        SchemaComparer.TablePair p = c.tables.stream().filter(x -> x.key.equalsIgnoreCase(key)).findFirst()
                .orElseThrow(() -> new NoSuchElementException("No table " + key + " in comparison " + id));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pair", p);
        out.put("source", p.sourceName == null ? null : TableView.of(c.sourceSchema.table(p.sourceName)));
        out.put("target", p.targetName == null ? null : TableView.of(c.targetSchema.table(p.targetName)));
        out.put("differences", c.differences.stream().filter(d -> p.key.equals(d.tableKey)).toList());
        return out;
    }

    public record ColumnView(String name, String type, boolean nullable, String defaultValue, String extra, String definition) {}

    public record TableView(String name, List<ColumnView> columns, List<String> primaryKey, List<String> indexes, List<String> foreignKeys, String options) {
        static TableView of(Table t) {
            if (t == null) return null;
            List<ColumnView> cols = new ArrayList<>();
            for (Column c : t.columns) {
                List<String> extra = new ArrayList<>();
                if (c.autoIncrement) extra.add("auto_increment");
                if (c.onUpdate != null) extra.add("on update " + c.onUpdate);
                if (c.generated != null) extra.add("generated");
                if (c.charset != null) extra.add(c.charset);
                if (c.comment != null && !c.comment.isEmpty()) extra.add("“" + c.comment + "”");
                cols.add(new ColumnView(c.name, c.typeText(), c.nullable, c.defaultValue, String.join(", ", extra), Sql.columnDefinition(c)));
            }
            List<String> opts = new ArrayList<>();
            if (t.engine != null) opts.add("ENGINE=" + t.engine);
            if (t.charset != null) opts.add("CHARSET=" + t.charset);
            if (t.collation != null) opts.add("COLLATE=" + t.collation);
            return new TableView(t.name, cols, t.primaryKey, t.indexes.values().stream().map(Sql::indexDefinition).toList(),
                    t.foreignKeys.values().stream().map(Sql::foreignKeyDefinition).toList(), String.join(" ", opts));
        }
    }

    // ------------------------------------------------------------------ scripts and report

    public record ScriptRequest(Map<String, ScriptWriter.Decision> decisions, String sourceSchema, String targetSchema,
                                String dataMode, boolean emptyTargetFirst, List<String> excludeTables) {}

    public ScriptWriter.Result scripts(String id, ScriptRequest req) {
        Stored s = stored(id);
        Map<String, ScriptWriter.Decision> decisions = req.decisions() == null ? Map.of() : req.decisions();
        ScriptWriter.Result r = ScriptWriter.write(s.comparison, decisions,
                new ScriptWriter.Options(req.sourceSchema(), req.targetSchema(), req.dataMode(), req.emptyTargetFirst(), req.excludeTables()));
        s.decisions = decisions;
        s.lastScripts = r;
        return r;
    }

    /** The differences with the choices last used for a script, as a plain black-and-white workbook. */
    public byte[] report(String id) throws IOException {
        Stored s = stored(id);
        Comparison c = s.comparison;
        Map<String, String> chosen = s.lastScripts == null ? Map.of() : s.lastScripts.actions();
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Font bold = wb.createFont(); bold.setBold(true); bold.setFontName("Arial"); bold.setFontHeightInPoints((short) 10);
            Font plain = wb.createFont(); plain.setFontName("Arial"); plain.setFontHeightInPoints((short) 10);
            Font big = wb.createFont(); big.setBold(true); big.setFontName("Arial"); big.setFontHeightInPoints((short) 14);
            CellStyle head = wb.createCellStyle(); head.setFont(bold); head.setBorderBottom(BorderStyle.THIN); head.setWrapText(true);
            head.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex()); head.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            CellStyle wrap = wb.createCellStyle(); wrap.setFont(plain); wrap.setWrapText(true); wrap.setVerticalAlignment(VerticalAlignment.TOP);
            CellStyle title = wb.createCellStyle(); title.setFont(big);

            Sheet sum = wb.createSheet("Summary");
            int r = 0;
            cell(sum, r++, 0, "Dry Dock schema comparison " + c.id, title);
            r++;
            String[][] rows = {
                    {"Source (changes come from)", c.source.fileName() + (c.source.database() == null ? "" : " · " + c.source.database())},
                    {"Target (is changed)", c.target.fileName() + (c.target.database() == null ? "" : " · " + c.target.database())},
                    {"Compared", c.createdAt},
                    {"Source tables / columns", c.source.tables() + " / " + c.source.columns()},
                    {"Target tables / columns", c.target.tables() + " / " + c.target.columns()},
                    {"Differences", String.valueOf(c.differences.size())},
                    {"Needs a decision (REVIEW)", String.valueOf(c.bySeverity.getOrDefault("REVIEW", 0))},
                    {"Could lose or change data (RISK)", String.valueOf(c.bySeverity.getOrDefault("RISK", 0))},
                    {"Choices", s.lastScripts == null ? "Defaults (no script written yet)" : "As used for the last script"},
            };
            for (String[] row : rows) { cell(sum, r, 0, row[0], head); cell(sum, r++, 1, row[1], wrap); }
            sum.setColumnWidth(0, 34 * 256); sum.setColumnWidth(1, 80 * 256);

            Sheet sh = wb.createSheet("Differences");
            String[] cols = {"Severity", "Area", "Difference", "Table", "Object", "Source", "Target", "Default action", "Chosen action", "What it means"};
            int[] widths = {10, 14, 15, 28, 28, 40, 40, 13, 13, 70};
            for (int i = 0; i < cols.length; i++) { cell(sh, 0, i, cols[i], head); sh.setColumnWidth(i, widths[i] * 256); }
            int i = 1;
            for (Difference d : c.differences) {
                String[] v = {d.severity, d.area.replace('_', ' '), d.kind.replace('_', ' '), d.table, d.object,
                        clip(d.source), clip(d.target), d.defaultAction, chosen.getOrDefault(d.id, d.defaultAction), d.summary};
                for (int k = 0; k < v.length; k++) cell(sh, i, k, v[k], wrap);
                i++;
            }
            sh.createFreezePane(0, 1);
            sh.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0, Math.max(0, i - 1), 0, cols.length - 1));
            for (Sheet x : List.of(sum, sh)) {
                x.getPrintSetup().setLandscape(true);
                x.getPrintSetup().setPaperSize(PrintSetup.A4_PAPERSIZE);
                x.setFitToPage(true);
                x.getPrintSetup().setFitWidth((short) 1);
                x.getPrintSetup().setFitHeight((short) 0);
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    private static String clip(String s) { return s == null ? "" : s.length() > 2000 ? s.substring(0, 2000) + " …" : s; }

    private static void cell(Sheet sh, int r, int c, String v, CellStyle style) {
        Row row = sh.getRow(r) == null ? sh.createRow(r) : sh.getRow(r);
        Cell cell = row.createCell(c);
        cell.setCellValue(v == null ? "" : v);
        cell.setCellStyle(style);
    }
}
