package br.com.olius.olius_core_api.repository;

import br.com.olius.olius_core_api.dto.NotificationContracts.Item;
import br.com.olius.olius_core_api.dto.NotificationContracts.Resource;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class NotificationRepository {
    private final JdbcTemplate jdbc;

    public NotificationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Item> list(UUID recipient, boolean unreadOnly, int page, int size) {
        return jdbc.query("""
                SELECT id, type, title, message, created_at, read_at, resource_type, resource_id
                FROM public.notification WHERE recipient_id = ? AND (NOT ? OR read_at IS NULL)
                ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?
                """,
                (rs, row) -> new Item(rs.getObject("id", UUID.class), rs.getString("type"),
                        rs.getString("title"), rs.getString("message"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("read_at", OffsetDateTime.class),
                        new Resource(rs.getString("resource_type"), rs.getObject("resource_id", UUID.class))),
                recipient, unreadOnly, size, (long) page * size);
    }

    public long count(UUID recipient, boolean unreadOnly) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM public.notification
                WHERE recipient_id = ? AND (NOT ? OR read_at IS NULL)
                """, Long.class, recipient, unreadOnly);
    }

    public int markRead(UUID recipient, UUID id) {
        return jdbc.update("""
                UPDATE public.notification SET read_at = COALESCE(read_at, clock_timestamp())
                WHERE recipient_id = ? AND id = ?
                """, recipient, id);
    }
}
