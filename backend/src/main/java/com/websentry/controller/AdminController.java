package com.websentry.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.websentry.config.WebSentryProperties;
import com.websentry.model.FlaggedUrl;
import com.websentry.repository.PagedScans;
import com.websentry.repository.ScanStore;
import com.websentry.service.StatsService;

/**
 * The endpoints the React dashboard calls.
 *
 * There is one hardcoded admin account, as agreed when the scope was reduced.
 * This is a documented limitation, not a security design.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private final ScanStore store;
    private final StatsService statsService;
    private final WebSentryProperties properties;

    public AdminController(ScanStore store, StatsService statsService, WebSentryProperties properties) {
        this.store = store;
        this.statsService = statsService;
        this.properties = properties;
    }

    /** Checks the single admin username and password. */
    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, String> body) {
        String username = body.getOrDefault("username", "");
        String password = body.getOrDefault("password", "");

        boolean ok = properties.getAdmin().getUsername().equals(username)
                && properties.getAdmin().getPassword().equals(password);

        if (!ok) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("ok", false, "message", "Wrong username or password."));
        }
        return ResponseEntity.ok(Map.of("ok", true, "username", username));
    }

    /** One page of every scan in the system, newest first. */
    @GetMapping("/scans")
    public PagedScans scans(@RequestParam(defaultValue = "0") int page,
                            @RequestParam(defaultValue = "25") int size) {
        return store.findAll(Math.max(0, page), Math.min(Math.max(1, size), 200));
    }

    /** Totals, risk band breakdown and scans per day. */
    @GetMapping("/stats")
    public Map<String, Object> stats(@RequestParam(defaultValue = "7") int days) {
        return statsService.summary(Math.min(Math.max(1, days), 90));
    }

    /** The websites flagged most often. */
    @GetMapping("/flagged")
    public List<FlaggedUrl> flagged(@RequestParam(defaultValue = "20") int limit) {
        return store.topFlagged(Math.min(Math.max(1, limit), 100));
    }

    /** Accuracy, precision, recall and F1 from the trained model. */
    @GetMapping("/model")
    public Map<String, Object> model() {
        return statsService.modelInfo();
    }
}
