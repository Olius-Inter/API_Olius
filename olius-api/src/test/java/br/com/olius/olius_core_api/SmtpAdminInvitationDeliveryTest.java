package br.com.olius.olius_core_api;

import br.com.olius.olius_core_api.service.AdminInvitationDelivery;
import br.com.olius.olius_core_api.service.SmtpAdminInvitationDelivery;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SmtpAdminInvitationDeliveryTest {
    private static final String TOKEN="a".repeat(43);
    private final JavaMailSender mail=mock(JavaMailSender.class);
    private final SmtpAdminInvitationDelivery delivery=new SmtpAdminInvitationDelivery(mail,
            "olius@example.invalid",URI.create("https://front.example.invalid/aceite"));
    private AdminInvitationDelivery.Message invitation(String token) {
        return new AdminInvitationDelivery.Message("Ana <teste>","ana@example.invalid",token);
    }
    @Test void sendsPlainTextToTheInviteeWithTheConfiguredFragmentLink() {
        delivery.send(invitation(TOKEN));
        var capture=ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail).send(capture.capture());
        var message=capture.getValue();
        assertThat(message.getTo()).containsExactly("ana@example.invalid");
        assertThat(message.getFrom()).isEqualTo("olius@example.invalid");
        assertThat(message.getText()).contains("Ana <teste>","24 horas",
                "https://front.example.invalid/aceite#token="+TOKEN);
        assertThat(message.getSubject()).doesNotContain(TOKEN);
        assertThat(invitation(TOKEN).toString()).doesNotContain(TOKEN,"ana@example.invalid");
    }
    @Test void refusesToSendWhileDatabaseTransactionIsActive() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try { assertThatThrownBy(() -> delivery.send(invitation(TOKEN))).isInstanceOf(IllegalStateException.class); }
        finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
        verifyNoInteractions(mail);
    }
    @Test void propagatesSmtpFailureWithoutRetrySoOrchestratorCanReportCommittedInvitation() {
        doThrow(new MailAuthenticationException("fixture authentication failure")).when(mail).send(any(SimpleMailMessage.class));
        assertThatThrownBy(() -> delivery.send(invitation(TOKEN))).isInstanceOf(MailAuthenticationException.class);
        verify(mail,times(1)).send(any(SimpleMailMessage.class));
    }
    @Test void rejectsUnexpectedTokenFormatBeforeSending() {
        assertThatThrownBy(() -> delivery.send(invitation("bad#token"))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(mail);
    }
}
