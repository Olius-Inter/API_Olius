package br.com.olius.olius_core_api.dto;

import br.com.olius.olius_core_api.entity.enums.RecordStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class DeliveryHistoryContracts {
    private DeliveryHistoryContracts() { }

    @Schema(name="CitizenDeliveryHistoryItem")
    public record Item(UUID id, UUID pevId, BigDecimal oilVolumeLiters,
                       Instant deliveryDate, Instant createdAt, long pointsEarned,
                       RecordStatus recordStatus, int revision) { }

    @Schema(name="CitizenDeliveryHistoryPage")
    public record Page(List<Item> items, int page, int size, long totalElements) {
        public Page { items=List.copyOf(items); }
    }
}
