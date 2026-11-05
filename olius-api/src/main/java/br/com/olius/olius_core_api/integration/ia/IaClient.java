package br.com.olius.olius_core_api.integration.ia;

import br.com.olius.olius_core_api.config.IaProperties;
import br.com.olius.olius_core_api.dto.ChatContracts.*;
import br.com.olius.olius_core_api.exception.IaIntegrationException;
import br.com.olius.olius_core_api.exception.enums.ApiErrorCode;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class IaClient implements AutoCloseable {
    private static final int MAX_RESPONSE_BYTES = 65536;
    private final IaProperties properties;
    private final HttpClient http;
    private final ObjectMapper mapper;

    public IaClient(IaProperties properties, HttpClient http, ObjectMapper mapper) {
        this.properties = properties;
        this.http = http;
        this.mapper = mapper;
    }

    public ChatResponse chat(ChatRequest request, String accessToken) {
        var json = post("/api/v1/chat", mapper.writeValueAsString(request), accessToken);
        if (!json.path("resposta").isString() || json.path("resposta").asText().isBlank()) {
            throw failure(ApiErrorCode.IA_INVALID_RESPONSE);
        }
        return new ChatResponse(json.path("resposta").asText());
    }

    public SessionResponse session(UUID sessionId, boolean start, String accessToken) {
        var json = post("/sessions/" + sessionId + (start ? "/iniciar" : "/encerrar"), null, accessToken);
        if (!json.path("session_id").isString()
                || !sessionId.toString().equals(json.path("session_id").asText())
                || !(json.path("resumo").isMissingNode() || json.path("resumo").isNull()
                     || json.path("resumo").isString())) {
            throw failure(ApiErrorCode.IA_INVALID_RESPONSE);
        }
        return new SessionResponse(sessionId, json.path("resumo").isString() ? json.path("resumo").asText() : null);
    }

    private JsonNode post(String path, String body, String token) {
        if (!properties.enabled()) throw failure(ApiErrorCode.IA_UNAVAILABLE);
        var request = HttpRequest.newBuilder(properties.baseUrl().resolve(URI.create(path)))
                .timeout(properties.requestTimeout())
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build();
        // O prazo cobre também a leitura completa do corpo; sem retry da operação.
        var pending = http.sendAsync(request, ignored -> new LimitedBodySubscriber());
        try {
            var response = pending.get(properties.requestTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (response.statusCode() != 200) throw failure(code(response.statusCode()));
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            if (!contentType.split(";", 2)[0].trim().equalsIgnoreCase("application/json")) {
                throw failure(ApiErrorCode.IA_INVALID_RESPONSE);
            }
            try {
                var json = mapper.readTree(response.body());
                if (json == null || !json.isObject()) throw failure(ApiErrorCode.IA_INVALID_RESPONSE);
                return json;
            } catch (tools.jackson.core.JacksonException invalid) {
                throw failure(ApiErrorCode.IA_INVALID_RESPONSE);
            }
        } catch (TimeoutException timeout) {
            pending.cancel(true);
            throw failure(ApiErrorCode.IA_TIMEOUT);
        } catch (InterruptedException interrupted) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw failure(ApiErrorCode.IA_UNAVAILABLE);
        } catch (ExecutionException unavailable) {
            for (Throwable cause = unavailable.getCause(); cause != null; cause = cause.getCause()) {
                if (cause instanceof HttpTimeoutException) throw failure(ApiErrorCode.IA_TIMEOUT);
                if (cause instanceof IaIntegrationException invalid) throw failure(invalid.getCode());
            }
            throw failure(ApiErrorCode.IA_UNAVAILABLE);
        }
    }

    @Override public void close() { http.close(); }

    private ApiErrorCode code(int status) {
        return switch (status) {
            case 401 -> ApiErrorCode.AUTHENTICATION_REQUIRED;
            case 403 -> ApiErrorCode.ACCESS_DENIED;
            case 404 -> ApiErrorCode.RESOURCE_NOT_FOUND;
            case 409 -> ApiErrorCode.RESOURCE_CONFLICT;
            case 400, 422 -> ApiErrorCode.BUSINESS_RULE_VIOLATION;
            case 429 -> ApiErrorCode.RATE_LIMITED;
            case 503 -> ApiErrorCode.IA_UNAVAILABLE;
            case 504 -> ApiErrorCode.IA_TIMEOUT;
            default -> ApiErrorCode.IA_INVALID_RESPONSE;
        };
    }

    private static IaIntegrationException failure(ApiErrorCode code) {
        return new IaIntegrationException(code);
    }

    /** Limita inclusive respostas chunked; não confia no Content-Length externo. */
    private static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;
        private int received;
        @Override public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
        @Override public void onSubscribe(Flow.Subscription value) {
            subscription = value;
            delegate.onSubscribe(value);
        }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (var buffer : buffers) {
                if (buffer.remaining() > MAX_RESPONSE_BYTES - received) {
                    subscription.cancel();
                    delegate.onError(failure(ApiErrorCode.IA_INVALID_RESPONSE));
                    return;
                }
                received += buffer.remaining();
            }
            delegate.onNext(buffers);
        }
        @Override public void onError(Throwable error) { delegate.onError(error); }
        @Override public void onComplete() { delegate.onComplete(); }
    }
}
