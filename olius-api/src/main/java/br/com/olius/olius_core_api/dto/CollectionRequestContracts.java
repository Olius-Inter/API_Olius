package br.com.olius.olius_core_api.dto;

import br.com.olius.olius_core_api.entity.enums.CollectionRequestStatus;
import jakarta.validation.constraints.*;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.format.annotation.DateTimeFormat;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class CollectionRequestContracts {
    private CollectionRequestContracts() { }

    @Schema(name="CollectionRequestCreate")
    public record Create(@NotNull @DecimalMin("0.01") @Digits(integer=6,fraction=2)
            BigDecimal estimatedVolumeLiters, @Pattern(regexp="(?s).*\\S.*") String observation) { }
    @Schema(name="CollectionRequestScheduling")
    public record Scheduling(@NotNull Instant scheduledAt, @NotNull Instant agreedAt, @NotBlank String reason) { }
    @Schema(name="CollectionRequestReason")
    public record Reason(@NotBlank String reason) { }

    @ParameterObject
    @Schema(name="CollectionRequestQuery")
    public record Query(CollectionRequestStatus status, UUID cycleId, @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) Instant from,
            @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) Instant until,
            @Min(0) Integer page, @Min(1) @Max(100) Integer size) {
        public Query { page=page==null ? 0 : page; size=size==null ? 20 : size; }
        @AssertTrue(message="Intervalo inválido")
        public boolean isPeriodValid() { return from==null || until==null || from.isBefore(until); }
    }
    @ParameterObject
    @Schema(name="CollectionRequestPagination")
    public record Pagination(@Min(0) Integer page, @Min(1) @Max(100) Integer size) {
        public Pagination { page=page==null ? 0 : page; size=size==null ? 20 : size; }
    }
    @Schema(name="CollectionRequestApproval")
    public record Approval(UUID approvedBy, Instant approvedAt) { }
    @Schema(name="CollectionRequestArrival")
    public record Arrival(Instant arrivedAt, Instant recordedAt, UUID driverId) { }
    @Schema(name="CollectionRequestAcceptance")
    public record Acceptance(Instant acceptedAt, Instant recordedAt) { }
    @Schema(name="CollectionRequestCancellation")
    public record Cancellation(Instant cancelledAt, UUID cancelledBy, String initiative, String policy,
            String reason, BigDecimal forfeitedVolumeLiters, int forfeitedCollectionSlots) { }
    @Schema(name="CollectionRequestView")
    public record RequestView(UUID id, UUID establishmentId, UUID subscriptionCycleId, CollectionRequestStatus status,
            BigDecimal estimatedVolumeLiters, String observation, Instant requestAt, Instant updatedAt,
            Instant scheduledAt, Approval approval, Arrival arrival, Acceptance acceptance,
            Cancellation cancellation, UUID collectionId) { }
    @Schema(name="CollectionRequestPage")
    public record Page(List<RequestView> items, int page, int size, long totalElements) {
        public Page { items=List.copyOf(items); }
    }
    @Schema(name="CollectionRequestScheduleChange")
    public record ScheduleChange(UUID id, Instant previousScheduledAt, Instant newScheduledAt,
            Instant agreedAt, Instant changedAt, UUID changedBy, String reason) { }
    @Schema(name="CollectionRequestSchedulePage")
    public record SchedulePage(List<ScheduleChange> items, int page, int size, long totalElements) {
        public SchedulePage { items=List.copyOf(items); }
    }
    @Schema(name="CollectionRequestAvailability")
    public record Availability(UUID cycleId, UUID establishmentId, BigDecimal volumeLimitLiters, int collectionLimit,
            BigDecimal reservedVolumeLiters, long reservedCollectionSlots,
            BigDecimal consumedVolumeLiters, long consumedCollectionSlots,
            BigDecimal forfeitedVolumeLiters, long forfeitedCollectionSlots,
            BigDecimal availableVolumeLiters, long availableCollectionSlots,
            boolean subscriptionActive, boolean cycleCurrent, boolean canAcceptNewRequest) { }
}
