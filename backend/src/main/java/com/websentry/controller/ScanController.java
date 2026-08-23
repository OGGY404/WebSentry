package com.websentry.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.websentry.model.ScanRecord;
import com.websentry.model.ScanRequest;
import com.websentry.service.ScanService;

import jakarta.validation.Valid;

/** The endpoints the browser extension calls. */
@RestController
@RequestMapping("/api/v1")
public class ScanController {

    private final ScanService scanService;

    public ScanController(ScanService scanService) {
        this.scanService = scanService;
    }

    /** Scan one website and return the full result. */
    @PostMapping("/scan")
    public ScanRecord scan(@Valid @RequestBody ScanRequest request,
                           @RequestParam(defaultValue = "true") boolean live) {
        return scanService.scan(request.url(), request.clientId(), live);
    }

    /** Look up one saved scan by its id. */
    @GetMapping("/scan/{id}")
    public ResponseEntity<ScanRecord> byId(@PathVariable String id) {
        return scanService.findById(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** The recent scans for one extension user, newest first. */
    @GetMapping("/history")
    public List<ScanRecord> history(@RequestParam String clientId,
                                    @RequestParam(defaultValue = "20") int limit) {
        return scanService.history(clientId, Math.min(limit, 100));
    }
}
