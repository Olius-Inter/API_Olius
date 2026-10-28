package br.com.olius.olius_core_api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class AdminInvitationResponses {
    private AdminInvitationResponses() { }

    public enum State { AVAILABLE, EXPIRED, REVOKED, ACCEPTED }

    public record Invitation(UUID id, String name, String email, Instant issuedAt,
            Instant expiresAt, UUID invitedBy, boolean bootstrap, Instant acceptedAt,
            UUID acceptedUserId, Instant revokedAt, UUID revokedBy, State state) { }

    public record Page(List<Invitation> items, int page, int size, long totalElements) {
        public Page { items = List.copyOf(items); }
    }
}
