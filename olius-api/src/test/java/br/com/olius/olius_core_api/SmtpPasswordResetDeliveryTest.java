package br.com.olius.olius_core_api;

import br.com.olius.olius_core_api.service.PasswordResetDelivery;
import br.com.olius.olius_core_api.service.SmtpPasswordResetDelivery;
import java.net.URI;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SmtpPasswordResetDeliveryTest {
    private final JavaMailSender mail=mock(JavaMailSender.class);
    private final SmtpPasswordResetDelivery delivery=new SmtpPasswordResetDelivery(mail,"olius@example.invalid",URI.create("https://front.example.invalid/reset"));
    private PasswordResetDelivery.Message message(String token) { return new PasswordResetDelivery.Message("recipient@example.invalid",token,Instant.parse("2026-11-01T12:30:00Z")); }
    @Test void sendsOneMessageWithFragmentAndActualExpiry() {
        var message=message("A".repeat(43)); delivery.send(message);
        var captor=ArgumentCaptor.forClass(SimpleMailMessage.class); verify(mail).send(captor.capture());
        assertThat(captor.getValue().getText()).contains("https://front.example.invalid/reset#token="+message.token(),message.expiresAt().toString());
        assertThat(captor.getValue().getSubject()).doesNotContain(message.token());
        assertThat(message.toString()).doesNotContain(message.email(),message.token());
    }
    @Test void rejectsSendInsideTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try { assertThatThrownBy(() -> delivery.send(message("A".repeat(43)))).isInstanceOf(IllegalStateException.class); }
        finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
        verifyNoInteractions(mail);
    }
    @Test void rejectsMalformedSecret() {
        assertThatThrownBy(() -> delivery.send(message("bad#token"))).isInstanceOf(IllegalArgumentException.class); verifyNoInteractions(mail);
    }
    @Test void smtpFailureIsPropagatedWithoutRetry() {
        doThrow(new MailSendException("fixture failure")).when(mail).send(any(SimpleMailMessage.class));
        var message=message("A".repeat(43));
        assertThatThrownBy(() -> delivery.send(message)).isInstanceOf(MailSendException.class);
        verify(mail,times(1)).send(any(SimpleMailMessage.class));
    }
}
