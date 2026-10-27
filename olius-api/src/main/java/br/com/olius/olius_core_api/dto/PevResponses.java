package br.com.olius.olius_core_api.dto;

import br.com.olius.olius_core_api.dto.IdentityResponses.AddressView;
import br.com.olius.olius_core_api.entity.enums.PevStatus;
import br.com.olius.olius_core_api.entity.enums.UserType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class PevResponses {
    private PevResponses() { }

    public record OwnPev(UUID id, PevStatus status, Instant createdAt, Instant approvedAt,
                         AddressView address) { }

    public record PublicPev(UUID id, AddressView address) { }

    public record PublicPage(List<PublicPev> items, int page, int size, long totalElements) { }
    public record AdminPev(UUID id, PevStatus status, Instant createdAt, Instant updatedAt,
                           Instant approvedAt, UUID ownerId, UserType ownerType, String ownerName,
                           AddressView address) { }
    public record AdminPage(List<AdminPev> items, int page, int size, long totalElements) { }
    public record AdminPevDetail(UUID id, PevStatus status, Instant createdAt, Instant updatedAt,
                                 Instant approvedAt, UUID approvedBy, UUID ownerId,
                                 UserType ownerType, String ownerName, AddressView address) { }
}
