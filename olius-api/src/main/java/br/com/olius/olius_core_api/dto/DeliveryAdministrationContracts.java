package br.com.olius.olius_core_api.dto;

import br.com.olius.olius_core_api.entity.enums.RecordStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class DeliveryAdministrationContracts {
    private DeliveryAdministrationContracts() { }

    public record Item(UUID id,UUID pevId,UUID citizenId,String citizenName,
                       UUID validatedBy,BigDecimal oilVolumeLiters,Instant deliveryDate,
                       Instant createdAt,long pointsEarned,RecordStatus recordStatus,int revision) { }
    public record Page(List<Item> items,int page,int size,long totalElements) {
        public Page { items=List.copyOf(items); }
    }
    public record Detail(Item delivery,long balanceAfter,Instant calculatedAt) { }
}
