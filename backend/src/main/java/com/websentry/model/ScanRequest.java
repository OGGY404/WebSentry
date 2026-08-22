package com.websentry.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * What the browser extension sends to POST /api/v1/scan.
 *
 * clientId is an anonymous UUID made by the extension. We never store a name
 * or an email, because the ethics form says no personal data is collected.
 */
public record ScanRequest(
        @NotBlank(message = "url is required")
        @Size(max = 4096, message = "url is too long")
        String url,

        @Size(max = 64, message = "clientId is too long")
        String clientId) {
}
