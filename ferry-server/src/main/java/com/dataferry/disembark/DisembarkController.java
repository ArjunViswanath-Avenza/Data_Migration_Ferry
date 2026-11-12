package com.dataferry.disembark;

import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Disembark: un-enrol one customer.
 *   GET  /api/disembark/profile   what will be looked up and removed (config/unenroll.json)
 *   POST /api/disembark/script    reviewable SQL; with a connection it also carries the preview
 *   POST /api/disembark/preview   read-only counts and checks (needs a connection)
 *   POST /api/disembark/execute   deletes, only when allowExecute is on
 *   GET  /api/disembark/history   recent runs
 */
@RestController
@RequestMapping("/api/disembark")
public class DisembarkController {

    private final DisembarkService service;

    public DisembarkController(DisembarkService service) { this.service = service; }

    @GetMapping("/profile")
    public UnenrollProfile profile() { return service.profile(); }

    @PostMapping("/script")
    public DisembarkService.ScriptResult script(@RequestBody DisembarkService.Request r) { return service.script(r); }

    @PostMapping("/preview")
    public UnenrollEngine.Plan preview(@RequestBody DisembarkService.Request r) { return service.preview(r); }

    @PostMapping("/execute")
    public DisembarkService.ExecuteResult execute(@RequestBody DisembarkService.Request r) { return service.execute(r); }

    @GetMapping("/history")
    public List<Map<String, Object>> history(@RequestParam(defaultValue = "20") int limit) { return service.history(Math.min(limit, 200)); }
}
