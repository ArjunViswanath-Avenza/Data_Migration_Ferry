package com.dataferry.crossing;

import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Crossing and its pre-flight Survey.
 *   POST /api/crossing/survey                 read-only checks against the target database
 *   POST /api/crossing/runs                   start a run of a planned batch
 *   GET  /api/crossing/runs?batchId=          recent runs
 *   GET  /api/crossing/runs/{id}              state, counts and contracts
 *   GET  /api/crossing/runs/{id}/contracts/{ref}   every step of one contract: request, answer, read-back
 *   POST /api/crossing/runs/{id}/continue     after the pilot or a stop rule
 *   POST /api/crossing/runs/{id}/stop         send nothing more
 */
@RestController
@RequestMapping("/api/crossing")
public class CrossingController {

    private final CrossingService crossing;
    private final SurveyService survey;

    public CrossingController(CrossingService crossing, SurveyService survey) {
        this.crossing = crossing;
        this.survey = survey;
    }

    @PostMapping("/survey")
    public SurveyService.Result survey(@RequestBody SurveyService.Request r) { return survey.survey(r); }

    @PostMapping("/runs")
    public Map<String, Object> start(@RequestBody CrossingService.StartRequest r) { return crossing.start(r); }

    @GetMapping("/runs")
    public List<Map<String, Object>> list(@RequestParam(required = false) String batchId, @RequestParam(defaultValue = "20") int limit) {
        return crossing.list(batchId, limit);
    }

    @GetMapping("/runs/{id}")
    public Map<String, Object> status(@PathVariable String id) { return crossing.status(id); }

    @GetMapping("/runs/{id}/contracts/{ref}")
    public List<Map<String, Object>> steps(@PathVariable String id, @PathVariable String ref) { return crossing.steps(id, ref); }

    public record Who(String performedBy) {}

    @PostMapping("/runs/{id}/continue")
    public Map<String, Object> resume(@PathVariable String id, @RequestBody(required = false) Who who) {
        return crossing.resume(id, who == null ? null : who.performedBy());
    }

    @PostMapping("/runs/{id}/stop")
    public Map<String, Object> stop(@PathVariable String id, @RequestBody(required = false) Who who) {
        return crossing.stop(id, who == null ? null : who.performedBy());
    }
}
