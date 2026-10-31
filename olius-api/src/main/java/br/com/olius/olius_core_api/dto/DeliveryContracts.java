package br.com.olius.olius_core_api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.List;
import br.com.olius.olius_core_api.entity.enums.RecordStatus;

public final class DeliveryContracts {
    private DeliveryContracts() { }

    @Schema(name="PevDeliveryRecord")
    public record Record(
        @NotNull UUID idempotencyKey,
        @Pattern(regexp="[A-Za-z0-9_-]{43}") String qrToken,
        @NotNull @DecimalMin(value="0", inclusive=false) @Digits(integer=3, fraction=2)
        BigDecimal oilVolumeLiters,
        @NotNull @PastOrPresent Instant deliveryDate
    ) {
        // O token pode faltar somente no reenvio de uma entrega confirmada.
        // O serviço decide esse caso após localizar a chave vinculada ao validador.
        @Override public String toString() {
            return "Record[idempotencyKey="+idempotencyKey+", qrToken=redacted, oilVolumeLiters="
                +oilVolumeLiters+", deliveryDate="+deliveryDate+"]";
        }
    }

    @Schema(name="PevDeliveryView")
    public record View(UUID id, UUID pevId, UUID citizenId, BigDecimal oilVolumeLiters,
        Instant deliveryDate, Instant createdAt, long pointsEarned, RecordStatus recordStatus,
        int revision, long balanceAfter) { }
    public record PevItem(UUID id, UUID citizenId, String citizenName, BigDecimal oilVolumeLiters,
        Instant deliveryDate, Instant createdAt, long pointsEarned, RecordStatus recordStatus,
        int revision) { }
    public record PevPage(List<PevItem> items, int page, int size, long totalElements) {
        public PevPage { items=List.copyOf(items); }
    }
}
