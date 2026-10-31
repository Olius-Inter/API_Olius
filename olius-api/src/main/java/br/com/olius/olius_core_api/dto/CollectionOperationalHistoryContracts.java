package br.com.olius.olius_core_api.dto;

import br.com.olius.olius_core_api.entity.enums.*;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class CollectionOperationalHistoryContracts {
    private CollectionOperationalHistoryContracts() { }

    @Schema(name="HistoricalCollectionFailureReason")
    public record HistoricalReason(UUID id,
            @Schema(description="Rótulo atual do catálogo; pode ser null. O vínculo histórico é o UUID.") String currentCode) { }

    @Schema(name="CollectionOperationalRevision")
    public record OperationalRevision(UUID collectionId,int revision,RecordStatus recordStatus,
            BigDecimal collectedVolumeLiters,BigDecimal presentedVolumeLiters,OilCondition oilCondition,
            boolean hasCompromisingOccurrence,CollectionResult result,String observation,
            List<HistoricalReason> failureReasons,UUID requestId,UUID establishmentId,UUID driverId,
            Instant collectionDate,Instant recordedAt,Instant changedAt,Instant correctedAt,
            UUID correctedBy,String correctionReason,String actorKind,UUID performedBy,
            UUID operationalDriverId,String auditReason,UUID auditEventId) {
        public OperationalRevision { failureReasons=List.copyOf(failureReasons); }
        @Override public String toString() { return "OperationalRevision[collectionId="+collectionId+", revision="+revision+"]"; }
    }

    @Schema(name="CollectionOperationalHistory")
    public record OperationalHistory(List<OperationalRevision> items,int page,int size,long totalElements) {
        public OperationalHistory { items=List.copyOf(items); }
    }
}
