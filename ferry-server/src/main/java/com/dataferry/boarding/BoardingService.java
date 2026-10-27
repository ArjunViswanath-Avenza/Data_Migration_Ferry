package com.dataferry.boarding;

import com.dataferry.boarding.MappingService.MappingProfile;
import com.dataferry.core.Ids;
import com.dataferry.store.RunStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

/**
 * Boarding, part one: get source rows into the canonical model as a batch. Upload never judges the
 * data: a row with a missing required field is still staged, so validation can say exactly which
 * rows are wrong instead of rejecting a whole file without explanation.
 */
@Service
public class BoardingService {

    private static final Logger log = LoggerFactory.getLogger(BoardingService.class);

    private final SheetReader reader;
    private final MappingService mapping;
    private final CanonicalModel model;
    private final BatchStore store;
    private final RunStore runs;

    public BoardingService(SheetReader reader, MappingService mapping, CanonicalModel model, BatchStore store, RunStore runs) {
        this.reader = reader;
        this.mapping = mapping;
        this.model = model;
        this.store = store;
        this.runs = runs;
    }

    /** What happened to one record type in an upload. */
    public record Staged(String recordType, String sheetName, String profileId, int rowsRead,
                         Map<String, String> resolvedColumns, List<String> unmappedColumns,
                         List<String> missingRequiredFields, List<Map<String, String>> sample) {}

    public record UploadResult(String batchId, String fileName, List<Staged> staged, List<String> sheetsIgnored) {}

    public String createBatch(String name, String sourceSystem, String by) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Give the batch a name.");
        String id = store.createBatch(name.trim(), sourceSystem == null || sourceSystem.isBlank() ? null : sourceSystem.trim(), by);
        log.info("Batch {} created: {} (source {})", id, name, sourceSystem);
        return id;
    }

    /**
     * Stages one file. With a record type, one sheet is read (the profile's sheet, the type's template
     * sheet, or the first sheet). Without one, every sheet whose name matches a record type is staged:
     * fill in the template workbook and upload it once.
     */
    public UploadResult upload(String batchId, MultipartFile file, String recordType, String profileId, String by) throws Exception {
        Map<String, Object> batch = store.batch(batchId);
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("No file was uploaded.");
        String sourceSystem = (String) batch.get("source_system");
        String fileName = file.getOriginalFilename();
        String runId = runs.start("boarding", "UPLOAD", batchId, by);
        try {
            LinkedHashMap<String, SheetReader.Sheet> sheets = reader.readAll(file.getBytes(), fileName);
            List<Staged> staged = new ArrayList<>();
            Set<String> used = new HashSet<>();

            if (recordType != null && !recordType.isBlank()) {
                String type = recordType.toUpperCase(Locale.ROOT);
                Optional<MappingProfile> profile = mapping.resolveProfile(profileId, type, sourceSystem);
                SheetReader.Sheet sheet = pick(sheets, type, profile.orElse(null), true);
                staged.add(stage(batchId, type, sheet, profile.orElse(null), fileName, by));
                used.add(key(sheet));
            } else {
                if (sheets.size() == 1 && sheets.containsKey(""))
                    throw new IllegalArgumentException("A CSV file holds one record type. Choose which one it is ("
                            + String.join(", ", model.types()) + ").");
                for (String type : model.types()) {
                    Optional<MappingProfile> profile = mapping.resolveProfile(null, type, sourceSystem);
                    SheetReader.Sheet sheet = pick(sheets, type, profile.orElse(null), false);
                    if (sheet == null || used.contains(key(sheet))) continue;
                    if (sheet.rows().isEmpty()) continue;   // template sheet left unfilled: nothing to stage for this type
                    staged.add(stage(batchId, type, sheet, profile.orElse(null), fileName, by));
                    used.add(key(sheet));
                }
                if (staged.isEmpty())
                    throw new IllegalArgumentException("No sheet in this file has data under a record-type name. Name the sheets "
                            + model.types().stream().map(t -> "'" + model.type(t).sheet + "'").toList()
                            + ", or choose a record type for the upload.");
            }

            List<String> ignored = sheets.values().stream().filter(s -> !used.contains(key(s)))
                    .map(s -> s.name() == null ? "(csv)" : s.name()).toList();
            int rows = staged.stream().mapToInt(Staged::rowsRead).sum();
            runs.finish(runId, "SUCCESS", rows, rows, 0, Map.of("file", String.valueOf(fileName),
                    "staged", staged.stream().map(s -> s.recordType() + "=" + s.rowsRead()).toList(), "sheetsIgnored", ignored));
            return new UploadResult(batchId, fileName, staged, ignored);
        } catch (Exception e) {
            runs.fail(runId, e.getMessage());
            throw e;
        }
    }

    /** Maps a file without staging it, to check a profile against real headers. */
    public Staged preview(MultipartFile file, String recordType, String profileId, String sourceSystem) throws Exception {
        String type = recordType.toUpperCase(Locale.ROOT);
        Optional<MappingProfile> profile = mapping.resolveProfile(profileId, type, sourceSystem);
        SheetReader.Sheet sheet = pick(reader.readAll(file.getBytes(), file.getOriginalFilename()), type, profile.orElse(null), true);
        MappingService.Mapped m = mapping.map(sheet, type, profile.orElse(null));
        return new Staged(type, sheet.name(), profile.map(p -> p.id).orElse(null), m.records().size(),
                m.resolvedColumns(), m.unmappedColumns(), m.missingRequiredFields(), sample(m.records(), 10));
    }

    private Staged stage(String batchId, String type, SheetReader.Sheet sheet, MappingProfile profile, String fileName, String by) {
        MappingService.Mapped m = mapping.map(sheet, type, profile);
        if (m.records().isEmpty()) throw new IllegalArgumentException("Sheet " + sheetLabel(sheet) + " has a header but no data rows.");
        store.replaceRecords(batchId, type, m.records());
        store.recordFile(batchId, type, fileName, sheet.name(), profile == null ? null : profile.id,
                m.records().size(), m.resolvedColumns(), m.unmappedColumns(), by);
        log.info("Batch {}: staged {} {} rows from {} {} (profile {}, unmapped {})", batchId, m.records().size(), type,
                fileName, sheetLabel(sheet), profile == null ? "none" : profile.id, m.unmappedColumns());
        return new Staged(type, sheet.name(), profile == null ? null : profile.id, m.records().size(),
                m.resolvedColumns(), m.unmappedColumns(), m.missingRequiredFields(), sample(m.records(), 5));
    }

    /** Profile's sheet, else the type's template sheet or label, else (when allowed) the first sheet. */
    private SheetReader.Sheet pick(LinkedHashMap<String, SheetReader.Sheet> sheets, String type, MappingProfile profile, boolean fallbackToFirst) {
        if (sheets.size() == 1 && sheets.containsKey("")) return sheets.get("");   // CSV
        CanonicalModel.RecordType rt = model.type(type);
        List<String> wanted = new ArrayList<>();
        if (profile != null && profile.sheet != null) wanted.add(profile.sheet);
        wanted.add(rt.sheet);
        wanted.add(rt.label);
        wanted.add(type);
        for (String w : wanted)
            for (Map.Entry<String, SheetReader.Sheet> e : sheets.entrySet())
                if (MappingService.norm(e.getKey()).equals(MappingService.norm(w))) return e.getValue();
        return fallbackToFirst ? sheets.values().iterator().next() : null;
    }

    private static List<Map<String, String>> sample(List<StagedRecord> records, int n) {
        return records.stream().limit(n).map(r -> (Map<String, String>) new LinkedHashMap<>(r.data)).toList();
    }

    private static String key(SheetReader.Sheet s) { return s.name() == null ? "" : s.name(); }

    private static String sheetLabel(SheetReader.Sheet s) { return s.name() == null ? "(csv)" : "'" + s.name() + "'"; }

    public void requireBatch(String batchId) {
        Ids.requireKey(batchId, "batch id");
        store.batch(batchId);
    }
}
