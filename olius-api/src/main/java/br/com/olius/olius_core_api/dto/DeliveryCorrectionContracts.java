package br.com.olius.olius_core_api.dto;

import br.com.olius.olius_core_api.entity.enums.RecordStatus;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;

public final class DeliveryCorrectionContracts {
    public static final int MAX_REASON_LENGTH=1000;
    private DeliveryCorrectionContracts() { }

    @Schema(name="PevDeliveryCorrection")
    public record Correction(
        @NotNull @Min(1) Integer expectedRevision,
        @NotNull RecordStatus recordStatus,
        @DecimalMin(value="0",inclusive=false) @Digits(integer=3,fraction=2)
        BigDecimal oilVolumeLiters,
        @PastOrPresent Instant deliveryDate,
        @NotBlank @Size(max=MAX_REASON_LENGTH) String reason
    ) {
        @AssertTrue(message="Volume e data devem ser enviados somente para RECORDED")
        @JsonIgnore
        @Schema(hidden=true)
        public boolean isStatusPayloadValid() {
            if(recordStatus==null) return true;
            return recordStatus==RecordStatus.ANNULLED
                ? oilVolumeLiters==null && deliveryDate==null
                : oilVolumeLiters!=null && deliveryDate!=null;
        }

        @Override public String toString() {
            return "Correction[expectedRevision="+expectedRevision+", recordStatus="+recordStatus
                +", oilVolumeLiters="+oilVolumeLiters+", deliveryDate="+deliveryDate
                +", reason=redacted]";
        }
    }
}
