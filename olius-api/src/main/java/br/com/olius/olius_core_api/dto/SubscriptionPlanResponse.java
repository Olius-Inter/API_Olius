package br.com.olius.olius_core_api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.UUID;

@Schema(description="Oferta ativa do OLIUS. Valores atuais não substituem uma cotação já emitida.")
public record SubscriptionPlanResponse(UUID id,String name,String description,
        @Schema(description="Preço mensal em reais (BRL)",example="19.90") BigDecimal monthlyPrice,
        @Schema(description="Franquia total de litros por ciclo mensal",example="100.00") BigDecimal volumeLimitLiters,
        @Schema(description="Quantidade máxima de coletas por ciclo mensal",example="2") Integer collectionLimit) { }
