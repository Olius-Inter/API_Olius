package br.com.olius.olius_core_api.controller;

import br.com.olius.olius_core_api.exception.ResourceNotFoundException;
import br.com.olius.olius_core_api.security.Caller;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/notifications")
@Tag(name = "Notificações")
public class NotificationController {
    private final JdbcTemplate jdbc;

    public NotificationController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    @Operation(summary = "Listar avisos da conta autenticada")
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ResponseEntity<Page> list(@Valid @ModelAttribute Query query, Authentication auth) {
        UUID recipient = Caller.id(auth);
        boolean unreadOnly = Boolean.TRUE.equals(query.unreadOnly());
        long total = count(recipient, unreadOnly);
        var items = jdbc.query("""
                SELECT id, type, title, message, created_at, read_at, resource_type, resource_id
                FROM public.notification WHERE recipient_id = ? AND (NOT ? OR read_at IS NULL)
                ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?
                """,
                (rs, row) -> new Item(rs.getObject("id", UUID.class), rs.getString("type"),
                        rs.getString("title"), rs.getString("message"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("read_at", OffsetDateTime.class),
                        new Resource(rs.getString("resource_type"), rs.getObject("resource_id", UUID.class))),
                recipient, unreadOnly, query.size(), (long) query.page() * query.size());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new Page(items, query.page(), query.size(), total));
    }

    @GetMapping("/unread-count")
    @Operation(summary = "Contar avisos não lidos da conta autenticada")
    public ResponseEntity<Count> unreadCount(Authentication auth) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new Count(count(Caller.id(auth), true)));
    }

    @PutMapping("/{id}/read")
    @Operation(summary = "Marcar um aviso próprio como lido, preservando a primeira leitura")
    public ResponseEntity<Void> markRead(@PathVariable UUID id, Authentication auth) {
        int rows = jdbc.update("""
                UPDATE public.notification SET read_at = COALESCE(read_at, clock_timestamp())
                WHERE recipient_id = ? AND id = ?
                """, Caller.id(auth), id);
        if (rows == 0) {
            throw new ResourceNotFoundException();
        }
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    private long count(UUID recipient, boolean unreadOnly) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM public.notification
                WHERE recipient_id = ? AND (NOT ? OR read_at IS NULL)
                """, Long.class, recipient, unreadOnly);
    }

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
