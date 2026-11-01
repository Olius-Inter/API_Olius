package br.com.olius.olius_core_api.service;

import java.net.URI;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public final class SmtpPasswordResetDelivery implements PasswordResetDelivery {
    private final JavaMailSender sender;
    private final String from;
    private final URI page;
    public SmtpPasswordResetDelivery(JavaMailSender sender,String from,URI page) {
        this.sender=sender; this.from=from; this.page=page;
    }
    @Override public void send(Message message) {
        if(TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Entrega exige emissão já confirmada");
        if(message.token()==null || !message.token().matches("[A-Za-z0-9_-]{43}"))
            throw new IllegalArgumentException("Segredo de recuperação inválido");
        var mail=new SimpleMailMessage();
        mail.setFrom(from); mail.setTo(message.email()); mail.setSubject("OLIUS — Recuperação de senha");
        mail.setText("""
                Recebemos uma solicitação para redefinir sua senha do OLIUS.
                Para escolher uma nova senha, abra:

                %s#token=%s

                O link expira em %s (UTC) e só pode ser utilizado uma vez.
                Após a troca, suas sessões serão encerradas. Faça login novamente.
                Se não solicitou a recuperação, ignore esta mensagem. Sua senha permanece inalterada.
                Não compartilhe este link. A equipe OLIUS não solicita sua senha por e-mail.
                """.formatted(page.toASCIIString(),message.token(),message.expiresAt()));
        sender.send(mail);
    }
}
