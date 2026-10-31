package br.com.olius.olius_core_api.dto;

import br.com.olius.olius_core_api.entity.enums.OilCondition;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springdoc.core.annotations.ParameterObject;
import static br.com.olius.olius_core_api.dto.CollectionContracts.*;

public final class CollectionCorrectionContracts {
    private CollectionCorrectionContracts() { }

    @Schema(name="CollectionCorrection")
    public record Correction(@NotNull @Min(1) Integer expectedRevision,
            @NotBlank @Size(max=MAX_NOTE_LENGTH) String justification,
            @NotNull @DecimalMin("0") @Digits(integer=6,fraction=2) BigDecimal collectedVolumeLiters,
            @NotNull @DecimalMin("0") @Digits(integer=6,fraction=2) BigDecimal presentedVolumeLiters,
            @NotNull OilCondition oilCondition,@NotNull Boolean hasCompromisingOccurrence,
            @NotNull @Size(max=MAX_FAILURE_REASON_ENTRIES) List<@NotBlank @Size(max=50) String> failureReasonCodes,
            @Size(max=MAX_NOTE_LENGTH) @Pattern(regexp="(?s).*\\S.*") String observation) {
        @Override public String toString() { return "Correction[redacted]"; }
    }

    @Schema(name="CollectionAnnulment")
    public record Annulment(@NotNull @Min(1) Integer expectedRevision,
            @NotBlank @Size(max=MAX_NOTE_LENGTH) String justification) {
        @Override public String toString() { return "Annulment[redacted]"; }
    }

    @ParameterObject @Schema(name="CollectionCalculationHistoryQuery")
    public record HistoryQuery(@Min(0) Integer page,@Min(1) @Max(100) Integer size) {
        public HistoryQuery { page=page==null ? 0 : page;size=size==null ? 20 : size; }
    }

    @Schema(name="CollectionCalculationRevision")
    public record CalculationRevision(UUID id,int revision,boolean current,long nominalPoints,
            long balanceBefore,long balanceAfter,Long successfulCollectionsCount,
            Instant calculatedAt,UUID recalculatedBy,String recalculationReason,List<PointComponent> components) {
        public CalculationRevision { components=List.copyOf(components); }
        @Override public String toString() { return "CalculationRevision[id="+id+", revision="+revision+"]"; }
    }

    @Schema(name="CollectionCalculationHistory")
    public record History(List<CalculationRevision> items,int page,int size,long totalElements) {
        public History { items=List.copyOf(items); }
    }
}
