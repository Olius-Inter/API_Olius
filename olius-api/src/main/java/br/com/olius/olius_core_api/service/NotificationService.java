package br.com.olius.olius_core_api.service;

import br.com.olius.olius_core_api.dto.NotificationContracts.Page;
import br.com.olius.olius_core_api.dto.NotificationContracts.Query;
import br.com.olius.olius_core_api.exception.ResourceNotFoundException;
import br.com.olius.olius_core_api.repository.NotificationRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationService {
    private final NotificationRepository notifications;

    public NotificationService(NotificationRepository notifications) {
        this.notifications = notifications;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page list(UUID recipient, Query query) {
        boolean unreadOnly = Boolean.TRUE.equals(query.unreadOnly());
        long total = notifications.count(recipient, unreadOnly);
        var items = notifications.list(recipient, unreadOnly, query.page(), query.size());
        return new Page(items, query.page(), query.size(), total);
    }

    @Transactional(readOnly = true)
    public long unreadCount(UUID recipient) {
        return notifications.count(recipient, true);
    }

    @Transactional
    public void markRead(UUID recipient, UUID id) {
        if (notifications.markRead(recipient, id) == 0) {
            throw new ResourceNotFoundException();
        }
    }
}
