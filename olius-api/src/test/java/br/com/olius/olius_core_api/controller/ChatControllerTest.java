package br.com.olius.olius_core_api.controller;

import br.com.olius.olius_core_api.dto.ChatContracts.*;
import br.com.olius.olius_core_api.exception.*;
import br.com.olius.olius_core_api.exception.enums.ApiErrorCode;
import br.com.olius.olius_core_api.integration.ia.IaClient;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ChatControllerTest {
    private final IaClient ia = mock(IaClient.class);
    private final UUID session = UUID.randomUUID();
    private final JwtAuthenticationToken auth = new JwtAuthenticationToken(
            Jwt.withTokenValue("validated-access-token").header("alg", "RS256").subject(UUID.randomUUID().toString()).build(), List.of());
    private MockMvc mvc;

    @BeforeEach void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new ChatController(ia))
                .setControllerAdvice(new GlobalExceptionHandler(new ApiProblemFactory(), new PersistenceExceptionTranslator()))
                .build();
    }

    @Test void usesAuthenticatedTokenInsteadOfCallerProvidedHeader() throws Exception {
        when(ia.chat(any(), eq("validated-access-token"))).thenReturn(new ChatResponse("Olá"));
        mvc.perform(post("/api/v1/chat").principal(auth).header("Authorization", "Bearer untrusted-header")
                .contentType(MediaType.APPLICATION_JSON).content(request("Olá")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.resposta").value("Olá"))
                .andExpect(header().string("Cache-Control", "no-store"));
        verify(ia).chat(new ChatRequest(session, "Olá"), "validated-access-token");
    }

    @Test void missingAuthenticationNeverCallsIa() throws Exception {
        mvc.perform(post("/api/v1/chat").contentType(MediaType.APPLICATION_JSON).content(request("Olá")))
                .andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate", "Bearer"));
        verifyNoInteractions(ia);
    }

    @Test void validatesBlankOversizedAndMissingSession() throws Exception {
        for (var body : List.of(request(" "), request("x".repeat(4001)), "{\"pergunta\":\"oi\"}")) {
            mvc.perform(post("/api/v1/chat").principal(auth).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
        verifyNoInteractions(ia);
    }

    @Test void validatesSessionPathAndForwardsBothOperations() throws Exception {
        when(ia.session(eq(session), anyBoolean(), anyString())).thenReturn(new SessionResponse(session, null));
        for (String action : List.of("iniciar", "encerrar")) {
            mvc.perform(post("/api/v1/chat/sessions/" + session + "/" + action).principal(auth))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.session_id").value(session.toString()));
        }
        verify(ia).session(session, true, "validated-access-token");
        verify(ia).session(session, false, "validated-access-token");
        mvc.perform(post("/api/v1/chat/sessions/not-a-uuid/iniciar").principal(auth)).andExpect(status().isBadRequest());
    }

    @Test void errorsUseCoreProblemContract() throws Exception {
        when(ia.chat(any(), anyString())).thenThrow(new IaIntegrationException(ApiErrorCode.IA_TIMEOUT,
                new java.net.http.HttpTimeoutException("private-transport-detail")));
        mvc.perform(post("/api/v1/chat").principal(auth).contentType(MediaType.APPLICATION_JSON).content(request("Olá")))
                .andExpect(status().isGatewayTimeout())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("IA_TIMEOUT"))
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .doesNotContain("validated-access-token", "private-transport-detail", "HttpTimeoutException"));
    }

    private String request(String question) {
        return "{\"session_id\":\"" + session + "\",\"pergunta\":\"" + question + "\"}";
    }
}
