package com.websentry.controller;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.websentry.config.WebSentryProperties;
import com.websentry.repository.ScanStore;
import com.websentry.service.ScanService;

/**
 * Tells you at a glance which parts are switched on. Useful before the demo:
 * one call shows whether the database, Claude and Safe Browsing are all live.
 */
@RestController
@RequestMapping("/api/v1")
public class HealthController {

    private final WebSentryProperties properties;
    private final ScanStore store;
    private final ScanService scanService;

    public HealthController(WebSentryProperties properties, ScanStore store, ScanService scanService) {
        this.properties = properties;
        this.store = store;
        this.scanService = scanService;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ok");
        body.put("scorer", scanService.activeScorer());
        body.put("storage", store.describe());
        body.put("claudeExplanations", properties.getAnthropic().isEnabled() ? "on" : "template fallback");
        body.put("safeBrowsing", properties.getSafeBrowsing().isEnabled() ? "on" : "off");
        body.put("mlServiceUrl", properties.getMl().getUrl());
        return body;
    }
}
