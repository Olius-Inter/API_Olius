package br.com.olius.olius_core_api.dto;

import br.com.olius.olius_core_api.entity.enums.CertificateStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.format.annotation.DateTimeFormat;

public final class CertificateContracts {
    private CertificateContracts() { }
    @Schema(name="CertificateLevel",description="Metadados atuais do catálogo; não são um snapshot histórico.")
    public record Level(UUID id,String name,String description,BigDecimal requiredLiters,String badgeImageUrl) { }
    @Schema(name="CertificateView",description="Estado atual oficial. revokedAt pode permanecer após reativação; consulte status.")
    public record View(UUID id,String code,UUID establishmentId,Level level,CertificateStatus status,
            Instant issuedAt,Instant createdAt,Instant updatedAt,Instant revokedAt,Instant reactivatedAt,String statusReason) { }
    @ParameterObject @Schema(name="CertificateQuery")
    public record Query(CertificateStatus status,UUID levelId,
            @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) Instant issuedFrom,
            @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) Instant issuedUntil,
            @Min(0) Integer page,@Min(1) @Max(100) Integer size) {
        public Query { page=page==null ? 0 : page;size=size==null ? 20 : size; }
        @AssertTrue(message="Intervalo de emissão inválido")
        public boolean isPeriodValid() { return issuedFrom==null || issuedUntil==null || issuedFrom.isBefore(issuedUntil); }
    }
    @Schema(name="CertificatePage")
    public record Page(List<View> items,int page,int size,long totalElements) {
        public Page { items=List.copyOf(items); }
    }
    @Schema(name="CertificateLevelProgress")
    public record LevelProgress(UUID levelId,String name,String description,BigDecimal requiredLiters,
            boolean reached,BigDecimal remainingLiters,UUID certificateId,CertificateStatus certificateStatus) { }
    @Schema(name="CertificateProgress",description="Acumulado sem limite anual. Meta alcançada não substitui certificado emitido; GET não reconcilia registros.")
    public record Progress(UUID establishmentId,BigDecimal eligibleVolumeLiters,List<LevelProgress> levels) {
        public Progress { levels=List.copyOf(levels); }
    }
}
