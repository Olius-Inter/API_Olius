package br.com.olius.olius_core_api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public final class ChatContracts {
    private ChatContracts() { }

    public record ChatRequest(@NotNull @JsonProperty("session_id") UUID sessionId,
                              @NotBlank @Size(max = 4000) String pergunta) { }
    public record ChatResponse(String resposta) { }
    public record SessionResponse(@JsonProperty("session_id") UUID sessionId, String resumo) { }
}
