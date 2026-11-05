package br.com.olius.olius_core_api.integration.ia;

import br.com.olius.olius_core_api.config.IaProperties;
import br.com.olius.olius_core_api.dto.ChatContracts.ChatRequest;
import br.com.olius.olius_core_api.exception.IaIntegrationException;
import br.com.olius.olius_core_api.exception.enums.ApiErrorCode;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class IaClientTransportTest {
    private ChatRequest question() { return new ChatRequest(UUID.randomUUID(), "Olá"); }

    private IaClient mockedClient(CompletableFuture<HttpResponse<byte[]>> result) {
        var http = mock(HttpClient.class);
        when(http.<byte[]>sendAsync(any(HttpRequest.class), any())).thenReturn(result);
        return new IaClient(new IaProperties(true, URI.create("http://ia"),
                Duration.ofSeconds(1), Duration.ofSeconds(3)), http, new ObjectMapper());
    }

    @Test void connectTimeoutIsUnavailableAndKeepsItsCause() {
        var cause = new HttpConnectTimeoutException("transport detail");
        var unavailable = mockedClient(CompletableFuture.failedFuture(cause));
        assertThatThrownBy(() -> unavailable.chat(question(), "test"))
                .isInstanceOfSatisfying(IaIntegrationException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(ApiErrorCode.IA_UNAVAILABLE);
                    assertThat(ex.getCause()).isSameAs(cause);
                    assertThat(ex.getMessage()).isEqualTo("IA_UNAVAILABLE");
                });
    }

    @Test void httpTimeoutKeepsItsCause() {
        var cause = new HttpTimeoutException("transport detail");
        var unavailable = mockedClient(CompletableFuture.failedFuture(cause));
        assertThatThrownBy(() -> unavailable.chat(question(), "test"))
                .isInstanceOfSatisfying(IaIntegrationException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(ApiErrorCode.IA_TIMEOUT);
                    assertThat(ex.getCause()).isSameAs(cause);
                });
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("transportFailures")
    void transportErrorsKeepRootDiagnosticWithoutChangingPublicMessage(Exception cause) {
        var unavailable = mockedClient(CompletableFuture.failedFuture(cause));
        assertThatThrownBy(() -> unavailable.chat(question(), "test"))
                .isInstanceOfSatisfying(IaIntegrationException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(ApiErrorCode.IA_UNAVAILABLE);
                    assertThat(ex.getCause()).isSameAs(cause);
                    assertThat(ex.getMessage()).isEqualTo("IA_UNAVAILABLE");
                });
    }

    static java.util.stream.Stream<Exception> transportFailures() {
        return java.util.stream.Stream.of(new java.net.UnknownHostException("private-host"),
                new javax.net.ssl.SSLException("private-tls-detail"));
    }

    @Test void totalDeadlineKeepsTimeoutCauseAndCancelsPendingRequest() throws Exception {
        @SuppressWarnings("unchecked")
        CompletableFuture<HttpResponse<byte[]>> result = mock(CompletableFuture.class);
        var cause = new TimeoutException("transport detail");
        when(result.get(anyLong(), any(TimeUnit.class))).thenThrow(cause);
        assertThatThrownBy(() -> mockedClient(result).chat(question(), "test"))
                .isInstanceOfSatisfying(IaIntegrationException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(ApiErrorCode.IA_TIMEOUT);
                    assertThat(ex.getCause()).isSameAs(cause);
                });
        verify(result).cancel(true);
    }

    @Test void interruptionKeepsCauseAndInterruptFlag() throws Exception {
        @SuppressWarnings("unchecked")
        CompletableFuture<HttpResponse<byte[]>> result = mock(CompletableFuture.class);
        var cause = new InterruptedException("transport detail");
        when(result.get(anyLong(), any(TimeUnit.class))).thenThrow(cause);
        try {
            assertThatThrownBy(() -> mockedClient(result).chat(question(), "test"))
                    .isInstanceOfSatisfying(IaIntegrationException.class, ex -> {
                        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.IA_UNAVAILABLE);
                        assertThat(ex.getCause()).isSameAs(cause);
                    });
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(result).cancel(true);
        } finally { Thread.interrupted(); }
    }

}
