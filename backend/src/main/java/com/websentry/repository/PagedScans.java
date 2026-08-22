package com.websentry.repository;

import java.util.List;

import com.websentry.model.ScanRecord;

/** One page of scan records for the dashboard table. */
public record PagedScans(List<ScanRecord> items, int page, int size, long totalItems, int totalPages) {
}
