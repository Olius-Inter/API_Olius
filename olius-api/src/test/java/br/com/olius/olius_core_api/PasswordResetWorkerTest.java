package br.com.olius.olius_core_api;

import br.com.olius.olius_core_api.config.PasswordResetProperties;
import br.com.olius.olius_core_api.repository.PasswordResetRepository.Issued;
import br.com.olius.olius_core_api.security.SecretTokens;
import br.com.olius.olius_core_api.service.PasswordResetDelivery;
import br.com.olius.olius_core_api.service.PasswordResetTransactions;
import br.com.olius.olius_core_api.service.PasswordResetWorker;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mail.MailSendException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class PasswordResetWorkerTest {
    private final PasswordResetTransactions transactions=mock(PasswordResetTransactions.class);
    private final PasswordResetDelivery delivery=mock(PasswordResetDelivery.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<PasswordResetDelivery> deliveries=mock(ObjectProvider.class);
    private final SecretTokens secrets=mock(SecretTokens.class);
    private static final String EMAIL="sensitive@example.invalid";
    private static final String TOKEN="X".repeat(43);
    private PasswordResetWorker worker(String result) {
        when(secrets.generate()).thenReturn(TOKEN); when(secrets.hash(TOKEN)).thenReturn(new byte[32]);
        when(deliveries.getObject()).thenReturn(delivery);
        when(transactions.issue(eq(EMAIL),any(byte[].class),eq(Duration.ofMinutes(30))))
            .thenReturn(new Issued(result,Instant.parse("2026-11-01T12:30:00Z")));
        return new PasswordResetWorker(transactions,secrets,new PasswordResetProperties(true,
            "https://front.example.invalid/reset","olius@example.invalid",Duration.ofMinutes(30)),deliveries);
    }
    @ParameterizedTest @ValueSource(strings={"USER_UNAVAILABLE","RATE_LIMITED","HASH_CONFLICT","INVALID_INPUT"})
    void rejectionNeverSendsMail(String code) { worker(code).process(EMAIL); verifyNoInteractions(delivery); }
    @Test void smtpFailureIsNotRetriedAndLogsNoSecrets(CapturedOutput output) {
        var worker=worker("OK");
        doThrow(new MailSendException(EMAIL+TOKEN+" password=secret-password")).when(delivery).send(any());
        worker.process(EMAIL);
        verify(transactions,times(1)).issue(eq(EMAIL),any(byte[].class),eq(Duration.ofMinutes(30)));
        verify(delivery,times(1)).send(any());
        assertThat(output.getAll()).contains("MailSendException").doesNotContain(EMAIL,TOKEN,"secret-password");
    }
    @Test void persistenceFailureDoesNotSendMail(CapturedOutput output) {
        var worker=worker("OK");
        when(transactions.issue(eq(EMAIL),any(byte[].class),any())).thenThrow(new IllegalStateException(EMAIL+TOKEN));
        worker.process(EMAIL); verifyNoInteractions(delivery);
        assertThat(output.getAll()).doesNotContain(EMAIL,TOKEN);
    }
}
