package com.dataferry.boarding;

import com.dataferry.core.Ids;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

/** Boarding: templates, batches, uploads, validation and the dry-run plan. */
@RestController
@RequestMapping("/api/boarding")
public class BoardingController {

    private static final MediaType XLSX = MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final CanonicalModel model;
    private final TemplateService templates;
    private final MappingService mapping;
    private final BoardingService boarding;
    private final ValidationService validation;
    private final PlanService planning;
    private final BatchStore store;

    public BoardingController(CanonicalModel model, TemplateService templates, MappingService mapping, BoardingService boarding,
                              ValidationService validation, PlanService planning, BatchStore store) {
        this.model = model;
        this.templates = templates;
        this.mapping = mapping;
        this.boarding = boarding;
        this.validation = validation;
        this.planning = planning;
        this.store = store;
    }

    // ---------------- model, templates, profiles ----------------

    @GetMapping("/model")
    public CanonicalModel.Model canonicalModel() { return model.model(); }

    @GetMapping("/templates/workbook.xlsx")
    public ResponseEntity<byte[]> workbookTemplate() throws Exception {
        return file("Ferry_Template_All.xlsx", templates.workbook());
    }

    @GetMapping("/templates/{recordType}.xlsx")
    public ResponseEntity<byte[]> template(@PathVariable String recordType) throws Exception {
        String type = Ids.requireKey(recordType, "record type").toUpperCase(Locale.ROOT);
        model.type(type);
        return file("Ferry_Template_" + type + ".xlsx", templates.single(type));
    }

    @GetMapping("/profiles")
    public List<MappingService.MappingProfile> profiles() { return mapping.profiles(); }

    @PostMapping(value = "/mapping/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public BoardingService.Staged preview(@RequestPart("file") MultipartFile file, @RequestParam String recordType,
                                          @RequestParam(required = false) String profileId,
                                          @RequestParam(required = false) String sourceSystem) throws Exception {
        return boarding.preview(file, recordType, profileId, sourceSystem);
    }

    // ---------------- batches ----------------

    public record NewBatch(String name, String sourceSystem, String performedBy) {}

    @PostMapping("/batches")
    public Map<String, Object> createBatch(@RequestBody NewBatch req) {
        String id = boarding.createBatch(req.name(), req.sourceSystem(), req.performedBy());
        return batch(id);
    }

    @GetMapping("/batches")
    public List<Map<String, Object>> batches(@RequestParam(defaultValue = "50") int limit) { return store.batches(limit); }

    @GetMapping("/batches/{batchId}")
    public Map<String, Object> batch(@PathVariable String batchId) {
        Map<String, Object> out = new LinkedHashMap<>(store.batch(Ids.requireKey(batchId, "batch id")));
        out.put("counts", store.counts(batchId));
        out.put("files", store.files(batchId));
        return out;
    }

    @PostMapping(value = "/batches/{batchId}/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public BoardingService.UploadResult upload(@PathVariable String batchId, @RequestPart("file") MultipartFile file,
                                               @RequestParam(required = false) String recordType,
                                               @RequestParam(required = false) String profileId,
                                               @RequestParam(required = false) String performedBy) throws Exception {
        Ids.requireKey(batchId, "batch id");
        return boarding.upload(batchId, file, recordType, profileId, performedBy);
    }

    @GetMapping("/batches/{batchId}/records")
    public List<StagedRecord> records(@PathVariable String batchId, @RequestParam(required = false) String type,
                                      @RequestParam(required = false) String status,
                                      @RequestParam(defaultValue = "500") int limit) {
        boarding.requireBatch(batchId);
        return store.records(batchId, type, status, limit);
    }

    // ---------------- validation and plan ----------------

    public record Actor(String performedBy) {}

    @PostMapping("/batches/{batchId}/validate")
    public ValidationService.Summary validate(@PathVariable String batchId, @RequestBody(required = false) Actor actor) {
        boarding.requireBatch(batchId);
        return validation.validate(batchId, actor == null ? null : actor.performedBy());
    }

    @GetMapping("/batches/{batchId}/findings")
    public Map<String, Object> findings(@PathVariable String batchId, @RequestParam(required = false) String severity,
                                        @RequestParam(required = false) String type,
                                        @RequestParam(defaultValue = "2000") int limit) {
        boarding.requireBatch(batchId);
        return Map.of("summary", store.findingSummary(batchId), "findings", store.findings(batchId, severity, type, limit));
    }

    @PostMapping("/batches/{batchId}/plan")
    public PlanService.Summary plan(@PathVariable String batchId, @RequestBody(required = false) Actor actor) {
        boarding.requireBatch(batchId);
        return planning.plan(batchId, actor == null ? null : actor.performedBy());
    }

    @GetMapping("/batches/{batchId}/plan")
    public List<Map<String, Object>> savedPlan(@PathVariable String batchId, @RequestParam(required = false) String status) {
        boarding.requireBatch(batchId);
        return store.plans(batchId, status);
    }

    /** Every record, in any batch, that relied on an assumption: what to re-check when its answer changes. */
    @GetMapping("/assumptions/{assumptionId}/records")
    public List<Map<String, Object>> taggedRecords(@PathVariable String assumptionId, @RequestParam(defaultValue = "1000") int limit) {
        return store.recordsTagged(Ids.requireKey(assumptionId, "assumption id"), limit);
    }

    private static ResponseEntity<byte[]> file(String name, byte[] bytes) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .contentType(XLSX).body(bytes);
    }
}
