package com.dataferry.drydock;

import com.dataferry.core.Ids;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;

/** Dry Dock: schema comparison and refit / data-move scripts. Reads files only; never connects to a database. */
@RestController
@RequestMapping("/api/drydock")
public class DryDockController {

    private final DryDockService service;

    public DryDockController(DryDockService service) { this.service = service; }

    @PostMapping(value = "/compare", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public SchemaComparer.Comparison compare(@RequestPart("source") MultipartFile source, @RequestPart("target") MultipartFile target,
                                             @RequestParam(defaultValue = "true") boolean ignoreIntDisplayWidth,
                                             @RequestParam(defaultValue = "true") boolean ignoreColumnOrder,
                                             @RequestParam(defaultValue = "false") boolean ignoreComments,
                                             @RequestParam(defaultValue = "false") boolean ignoreCollations,
                                             @RequestParam(defaultValue = "true") boolean ignoreRowFormat,
                                             @RequestParam(defaultValue = "true") boolean detectRenames) {
        if (source.isEmpty() || target.isEmpty()) throw new IllegalArgumentException("Choose both a source and a target file.");
        return service.compare(input(source), input(target),
                new SchemaComparer.Options(ignoreIntDisplayWidth, ignoreColumnOrder, ignoreComments, ignoreCollations, ignoreRowFormat, detectRenames));
    }

    /** Compares the two bundled sample files (samples/drydock), to try the area without your own files. */
    @PostMapping("/compare-samples")
    public SchemaComparer.Comparison compareSamples() { return service.compareSamples(SchemaComparer.Options.defaults()); }

    private static DryDockService.Input input(MultipartFile f) {
        return new DryDockService.Input(f.getOriginalFilename(), () -> {
            try { return f.getInputStream(); } catch (IOException e) { throw new UncheckedIOException(e); }
        });
    }

    @GetMapping("/comparisons")
    public List<DryDockService.Listed> list() { return service.list(); }

    @GetMapping("/comparisons/{id}")
    public SchemaComparer.Comparison get(@PathVariable String id) { return service.get(Ids.requireKey(id, "comparison id")); }

    @GetMapping("/comparisons/{id}/tables/{key}")
    public Map<String, Object> table(@PathVariable String id, @PathVariable String key) {
        return service.table(Ids.requireKey(id, "comparison id"), key);
    }

    @PostMapping("/comparisons/{id}/scripts")
    public ScriptWriter.Result scripts(@PathVariable String id, @RequestBody DryDockService.ScriptRequest req) {
        return service.scripts(Ids.requireKey(id, "comparison id"), req);
    }

    @GetMapping("/comparisons/{id}/report.xlsx")
    public ResponseEntity<byte[]> report(@PathVariable String id) throws IOException {
        String cid = Ids.requireKey(id, "comparison id");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"DryDock_" + cid + ".xlsx\"")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(service.report(cid));
    }
}
