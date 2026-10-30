package br.com.olius.olius_core_api.dto;

import br.com.olius.olius_core_api.entity.enums.*;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.format.annotation.DateTimeFormat;

public final class CollectionContracts {
    private CollectionContracts() { }
    @Schema(name="OperationalDriverAccess")
    public record DriverAccess(@NotNull @Pattern(regexp="[0-9]{11}") String cpf,
            @NotNull @Pattern(regexp="[0-9]{11}") String cnh,@NotBlank @Size(max=64) String qrToken) {
        @Override public String toString() { return "DriverAccess[redacted]"; }
    }
    @Schema(name="CollectionRecord")
    public record Record(@NotNull @Valid DriverAccess access,
            @NotNull @DecimalMin("0") @Digits(integer=6,fraction=2) BigDecimal collectedVolumeLiters,
            @NotNull @DecimalMin("0") @Digits(integer=6,fraction=2) BigDecimal presentedVolumeLiters,
            @NotNull OilCondition oilCondition,@NotNull Boolean hasCompromisingOccurrence,
            @NotNull List<@NotBlank @Size(max=50) String> failureReasonCodes,
            @Pattern(regexp="(?s).*\\S.*") String observation) { }
    @Schema(name="AdministrativeFailedVisit")
    public record FailedVisit(@NotNull AdministrativeFailureReason reasonCode,@NotBlank String justification) { }
    @ParameterObject @Schema(name="CollectionQuery")
    public record Query(CollectionResult result,RecordStatus recordStatus,
            @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) Instant from,
            @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) Instant until,
            @Min(0) Integer page,@Min(1) @Max(100) Integer size) {
        public Query { page=page==null ? 0 : page;size=size==null ? 20 : size; }
        @AssertTrue(message="Intervalo inválido")
        public boolean isPeriodValid() { return from==null || until==null || from.isBefore(until); }
    }
    @ParameterObject @Schema(name="CollectionMetricsPeriod")
    public record Period(@DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) Instant from,
            @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) Instant until) {
        @AssertTrue(message="Intervalo inválido")
        public boolean isPeriodValid() { return from==null || until==null || from.isBefore(until); }
    }
    @Schema(name="OperationalRequest")
    public record OperationalRequest(UUID id,BigDecimal estimatedVolumeLiters,Instant scheduledAt,
            Instant arrivedAt,Instant serviceAcceptedAt) { }
    @Schema(name="CollectionFailureReason")
    public record FailureReason(String code,String name,String description) { }
    @Schema(name="OperationalCollectionContext")
    public record Context(UUID driverId,String driverName,UUID establishmentId,String establishmentName,
            List<OperationalRequest> requests,int page,int size,long totalElements,List<FailureReason> failureReasons) {
        public Context { requests=List.copyOf(requests);failureReasons=List.copyOf(failureReasons); }
    }
    @Schema(name="CollectionView")
    public record View(UUID id,UUID requestId,UUID establishmentId,UUID driverId,
            BigDecimal collectedVolumeLiters,BigDecimal presentedVolumeLiters,OilCondition oilCondition,
            boolean hasCompromisingOccurrence,CollectionResult result,long pointsEarned,String observation,
            Instant collectionDate,Instant createdAt,long processingOrder,RecordStatus recordStatus,int revision,
            List<String> failureReasonCodes) { public View { failureReasonCodes=List.copyOf(failureReasonCodes); } }
    @Schema(name="CollectionPointComponent")
    public record PointComponent(String component,long points) { }
    @Schema(name="CollectionPointCalculation")
    public record Calculation(UUID id,int revision,long nominalPoints,long balanceBefore,long balanceAfter,
            Long successfulCollectionsCount,List<PointComponent> components) {
        public Calculation { components=List.copyOf(components); }
    }
    @Schema(name="CollectionDetail")
    public record Detail(View collection,Calculation calculation) { }
    @Schema(name="CollectionPage")
    public record Page(List<View> items,int page,int size,long totalElements) { public Page { items=List.copyOf(items); } }
    @Schema(name="DriverCollectionMetrics")
    public record Metrics(long visits,BigDecimal collectedVolumeLiters,long establishmentsVisited,
            Instant lastCollectionDate,long successfulVisits,long unsuccessfulVisits) { }
    @Schema(name="EstablishmentScore")
    public record Score(long points,long successfulCollectionsCount,BigDecimal collectedVolumeLiters) { }
}
