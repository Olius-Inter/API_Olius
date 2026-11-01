package br.com.olius.olius_core_api;

import br.com.olius.olius_core_api.config.PasswordResetProperties;
import br.com.olius.olius_core_api.dto.PasswordResetRequests;
import br.com.olius.olius_core_api.exception.PasswordResetInvalidException;
import br.com.olius.olius_core_api.security.SecretTokens;
import br.com.olius.olius_core_api.service.PasswordResetService;
import br.com.olius.olius_core_api.service.PasswordResetTransactions;
import br.com.olius.olius_core_api.service.PasswordResetWorker;
import java.time.Duration;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PasswordResetServiceTest {
    @SuppressWarnings("unchecked")
    private final ObjectProvider<ThreadPoolTaskExecutor> executors=mock(ObjectProvider.class);
    private final ThreadPoolTaskExecutor executor=mock(ThreadPoolTaskExecutor.class);
    private final PasswordResetWorker worker=mock(PasswordResetWorker.class);
    private final PasswordResetTransactions transactions=mock(PasswordResetTransactions.class);
    private final PasswordEncoder passwords=mock(PasswordEncoder.class);
    private final SecretTokens secrets=new SecretTokens();
    private PasswordResetService service(boolean enabled) {
        when(executors.getIfAvailable()).thenReturn(executor);
        return new PasswordResetService(new PasswordResetProperties(enabled,"https://front.example.invalid/reset",
            "olius@example.invalid",Duration.ofMinutes(30)),executors,worker,transactions,secrets,passwords);
    }
    @Test void enqueuesEveryRequestWithoutLookupOrSmtpInHttpThread() {
        var service=service(true);
        service.request(new PasswordResetRequests.Request("Pessoa@EXAMPLE.INVALID"));
        service.request(new PasswordResetRequests.Request("missing@example.invalid"));
        verifyNoInteractions(worker,transactions,passwords);
        var tasks=ArgumentCaptor.forClass(Runnable.class); verify(executor,times(2)).execute(tasks.capture());
        tasks.getAllValues().forEach(Runnable::run);
        verify(worker).process("Pessoa@example.invalid"); verify(worker).process("missing@example.invalid");
    }
    @Test void queueRejectionReturns503WithoutRunningTask() {
        var service=service(true); doThrow(new RejectedExecutionException()).when(executor).execute(any(Runnable.class));
        var request=new PasswordResetRequests.Request("someone@example.invalid");
        assertThatThrownBy(() -> service.request(request)).isInstanceOfSatisfying(ResponseStatusException.class,
            error -> assertThat(error.getStatusCode().value()).isEqualTo(503));
        verifyNoInteractions(worker,transactions);
    }
    @Test void disabledFeatureDoesNotHashIssueOrConsume() {
        var service=service(false); var request=new PasswordResetRequests.Request("someone@example.invalid");
        var completion=new PasswordResetRequests.Complete(secrets.generate(),"Password1!");
        assertThatThrownBy(() -> service.request(request)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.complete(completion)).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(worker,transactions,passwords,executor);
    }
    @Test void malformedSecretNeverReachesPersistenceOrEncoder() {
        var service=service(true); var request=new PasswordResetRequests.Complete("bad#token","Password1!");
        assertThatThrownBy(() -> service.complete(request)).isInstanceOf(PasswordResetInvalidException.class);
        verifyNoInteractions(transactions,passwords);
    }
    @Test void unexpectedDatabaseCodeCannotBeReportedAsSuccess() {
        var service=service(true); String token=secrets.generate();
        when(passwords.encode("Password1!")).thenReturn("encoded-password");
        when(transactions.complete(any(byte[].class),eq("encoded-password"))).thenReturn("INVALID_INPUT");
        var request=new PasswordResetRequests.Complete(token,"Password1!");
        assertThatThrownBy(() -> service.complete(request)).isInstanceOf(IllegalStateException.class);
        verify(transactions).complete(org.mockito.AdditionalMatchers.aryEq(secrets.hash(token)),eq("encoded-password"));
    }
}
