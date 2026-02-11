package com.example.ordertracking.application.port.out;

import java.time.Instant;

public record OrderSearchQuery(String orderIdContains,
                               String customerIdContains,
                               String status,
                               Instant updatedFrom,
                               Instant updatedTo,
                               int page,
                               int size,
                               String sortBy,
                               String sortDir) {
    public OrderSearchQuery {
        int normalizedPage = Math.max(page, 0);
        int normalizedSize = size <= 0 ? 50 : Math.min(size, 500);
        String normalizedSortBy = (sortBy == null || sortBy.isBlank()) ? "updatedAt" : sortBy;
        String normalizedSortDir = (sortDir == null || sortDir.isBlank()) ? "desc" : sortDir;

        page = normalizedPage;
        size = normalizedSize;
        sortBy = normalizedSortBy;
        sortDir = normalizedSortDir;
    }
}
