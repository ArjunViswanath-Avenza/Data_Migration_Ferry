package com.dataferry.mock;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * The mock target's two faces: the vendor-shaped API at /mock (login and DataMigration operations),
 * and its control panel at /api/mock (behaviour switches, reset, what it holds).
 */
@RestController
public class MockTargetController {

    private final MockTarget mock;

    public MockTargetController(MockTarget mock) { this.mock = mock; }

    @PostMapping("/mock/login")
    public ResponseEntity<Map<String, Object>> login(@RequestHeader(value = "X-Kony-App-Key", required = false) String key,
                                                     @RequestHeader(value = "X-Kony-App-Secret", required = false) String secret) {
        return reply(mock.login(key, secret));
    }

    @PostMapping("/mock/services/DataMigration/{operation}")
    public ResponseEntity<Map<String, Object>> call(@PathVariable String operation,
                                                    @RequestHeader(value = "X-Kony-Authorization", required = false) String token,
                                                    @RequestBody(required = false) Map<String, Object> body) {
        return reply(mock.call(operation, token, body == null ? Map.of() : body));
    }

    private static ResponseEntity<Map<String, Object>> reply(MockTarget.Reply r) {
        if (r.delayMs() > 0) {
            try { Thread.sleep(r.delayMs()); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        return ResponseEntity.status(r.httpStatus()).body(r.body());
    }

    @GetMapping("/api/mock/behaviour")
    public MockTarget.Behaviour behaviour() { return mock.behaviour(); }

    @PutMapping("/api/mock/behaviour")
    public MockTarget.Behaviour behaviour(@RequestBody MockTarget.Behaviour b) { mock.behaviour(b); return mock.behaviour(); }

    @PostMapping("/api/mock/reset")
    public Map<String, Object> reset() { mock.reset(); mock.behaviour(new MockTarget.Behaviour()); return mock.state(); }

    @GetMapping("/api/mock/state")
    public Map<String, Object> state() { return mock.state(); }
}
