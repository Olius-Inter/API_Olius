package br.com.olius.olius_core_api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class NotificationContracts {
    private NotificationContracts() {}

    public record Query(@Min(0) Integer page, @Min(1) @Max(100) Integer size, Boolean unreadOnly) {
        public Query {
            page = page == null ? 0 : page;
            size = size == null ? 20 : size;
            unreadOnly = Boolean.TRUE.equals(unreadOnly);
        }
    }

    public record Page(List<Item> items, int page, int size, long total) {}
    public record Item(UUID id, String type, String title, String message,
                       OffsetDateTime createdAt, OffsetDateTime readAt, Resource resource) {}
    public record Resource(String type, UUID id) {}
    public record Count(long count) {}
}
