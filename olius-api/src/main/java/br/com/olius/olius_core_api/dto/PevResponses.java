package br.com.olius.olius_core_api.dto;

import br.com.olius.olius_core_api.dto.IdentityResponses.AddressView;
import br.com.olius.olius_core_api.entity.enums.PevStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class PevResponses {
    private PevResponses() { }

    public record OwnPev(UUID id, PevStatus status, Instant createdAt, Instant approvedAt,
                         AddressView address) { }

    public record PublicPev(UUID id, AddressView address) { }

    public record PublicPage(List<PublicPev> items, int page, int size, long totalElements) { }
}
