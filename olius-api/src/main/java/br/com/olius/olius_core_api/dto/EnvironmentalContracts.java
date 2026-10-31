package br.com.olius.olius_core_api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;

public final class EnvironmentalContracts {
    private EnvironmentalContracts() { }
    @Schema(name="B2bEnvironmentalMetrics")
    public record Collections(long count,long successful,long unsuccessful,BigDecimal collectedVolumeLiters,long establishments) { }
    @Schema(name="B2cEnvironmentalMetrics")
    public record Deliveries(long count,BigDecimal acceptedVolumeLiters,long citizens,long pevs) { }
    @Schema(name="EnvironmentalSummary",description="Consultas atuais por período físico [from,until). B2B e B2C são separados para evitar dupla contagem. Não é documento comercial emitido ou arquivado.")
    public record Summary(Instant from,Instant until,Collections collections,Deliveries deliveries) { }
}
