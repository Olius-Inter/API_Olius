package br.com.olius.olius_core_api.integration.ia;

import br.com.olius.olius_core_api.config.IaProperties;
import br.com.olius.olius_core_api.dto.ChatContracts.ChatRequest;
import br.com.olius.olius_core_api.exception.IaIntegrationException;
import br.com.olius.olius_core_api.exception.enums.ApiErrorCode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

class IaClientTest {
    private HttpServer server;
    private java.util.concurrent.ExecutorService executor;
    private final UUID session = UUID.randomUUID();
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();
    private int status = 200;
    private String contentType = "application/json";
    private String response = "{\"resposta\":\"Olá\"}";
    private long delay;
    private boolean delayBody;

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            path.set(exchange.getRequestURI().toString());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            try {
                if (!delayBody) Thread.sleep(delay);
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", contentType);
                exchange.getResponseHeaders().set("Location", "/redirected");
                exchange.sendResponseHeaders(status, bytes.length);
                if (delayBody) Thread.sleep(delay);
                exchange.getResponseBody().write(bytes);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally { exchange.close(); }
        });
        server.start();
    }

    @AfterEach void stop() {
        if (server != null) server.stop(0);
        if (executor != null) executor.shutdownNow();
    }

    private IaClient client(boolean enabled, Duration timeout) {
        return new IaClient(new IaProperties(enabled,
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                Duration.ofMillis(100), timeout),
                HttpClient.newBuilder().connectTimeout(Duration.ofMillis(100))
                        .followRedirects(HttpClient.Redirect.NEVER).build(), new ObjectMapper());
    }

    private IaClient client() { return client(true, Duration.ofSeconds(3)); }
    private ChatRequest question() { return new ChatRequest(session, "Olá"); }

    @Test void forwardsBearerAndOnlyTheDocumentedChatFields() throws Exception {
        assertThat(client().chat(question(), "test-access-token").resposta()).isEqualTo("Olá");
        assertThat(path.get()).isEqualTo("/api/v1/chat");
        assertThat(authorization.get()).isEqualTo("Bearer test-access-token");
        var sent = new ObjectMapper().readTree(body.get());
        assertThat(sent.size()).isEqualTo(2);
        assertThat(sent.path("session_id").asText()).isEqualTo(session.toString());
        assertThat(sent.path("pergunta").asText()).isEqualTo("Olá");
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test void startsAndEndsTheSameConversation() {
        response = "{\"session_id\":\"" + session + "\",\"resumo\":null}";
        assertThat(client().session(session, true, "test").sessionId()).isEqualTo(session);
        assertThat(path.get()).isEqualTo("/sessions/" + session + "/iniciar");
        assertThat(body.get()).isEmpty();
        response = "{\"session_id\":\"" + session + "\",\"resumo\":\"Resumo\"}";
        assertThat(client().session(session, false, "test").resumo()).isEqualTo("Resumo");
        assertThat(path.get()).isEqualTo("/sessions/" + session + "/encerrar");
    }

    @ParameterizedTest
    @CsvSource({"401,AUTHENTICATION_REQUIRED", "403,ACCESS_DENIED", "404,RESOURCE_NOT_FOUND",
        "409,RESOURCE_CONFLICT", "422,BUSINESS_RULE_VIOLATION", "429,RATE_LIMITED",
        "500,IA_INVALID_RESPONSE", "503,IA_UNAVAILABLE", "504,IA_TIMEOUT", "302,IA_INVALID_RESPONSE"})
    void translatesStatusWithoutLeakingUpstreamContentOrRetry(int value, ApiErrorCode expected) {
        status = value;
        response = "private-upstream-detail";
        assertThatThrownBy(() -> client().chat(question(), "test"))
                .isInstanceOfSatisfying(IaIntegrationException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(expected);
                    assertThat(ex.getMessage()).doesNotContain(response);
                });
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test void disabledIntegrationDoesNotCallIa() {
        assertThatThrownBy(() -> client(false, Duration.ofSeconds(1)).chat(question(), "test"))
                .isInstanceOfSatisfying(IaIntegrationException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(ApiErrorCode.IA_UNAVAILABLE));
        assertThat(calls.get()).isZero();
    }

    @Test void timeoutDoesNotRetry() {
        delay = 1500;
        assertThatThrownBy(() -> client(true, Duration.ofMillis(300)).chat(question(), "test"))
                .isInstanceOfSatisfying(IaIntegrationException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(ApiErrorCode.IA_TIMEOUT));
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test void timeoutAlsoLimitsReadingTheResponseBody() {
        delay = 1500;
        delayBody = true;
        assertThatThrownBy(() -> client(true, Duration.ofMillis(300)).chat(question(), "test"))
                .isInstanceOfSatisfying(IaIntegrationException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(ApiErrorCode.IA_TIMEOUT));
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"not-json", "{}", "[]", "null", "{\"resposta\":9}", "{\"resposta\":\"\"}"})
    void rejectsMalformedResponses(String value) {
        response = value;
        assertThatThrownBy(() -> client().chat(question(), "test"))
                .isInstanceOfSatisfying(IaIntegrationException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(ApiErrorCode.IA_INVALID_RESPONSE));
    }

    @Test void rejectsHtmlAndMismatchedSession() {
        contentType = "text/html";
        assertThatThrownBy(() -> client().chat(question(), "test"))
                .isInstanceOfSatisfying(IaIntegrationException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(ApiErrorCode.IA_INVALID_RESPONSE));
        contentType = "application/json";
        response = "{\"session_id\":\"" + UUID.randomUUID() + "\"}";
        assertThatThrownBy(() -> client().session(session, true, "test")).isInstanceOf(IaIntegrationException.class);
    }

    @Test void limitsResponseSize() {
        response = "x".repeat(70000);
        assertThatThrownBy(() -> client().chat(question(), "test")).isInstanceOf(IaIntegrationException.class);
    }

    @Test void connectionFailureIsUnavailable() {
        var unavailable = client();
        server.stop(0);
        assertThatThrownBy(() -> unavailable.chat(question(), "test"))
                .isInstanceOfSatisfying(IaIntegrationException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(ApiErrorCode.IA_UNAVAILABLE));
    }
}
