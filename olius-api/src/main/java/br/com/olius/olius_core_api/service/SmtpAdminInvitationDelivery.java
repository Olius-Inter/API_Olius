package br.com.olius.olius_core_api.service;

import java.net.URI;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Envio síncrono após commit; sem retry automático ou registro do conteúdo. */
public final class SmtpAdminInvitationDelivery implements AdminInvitationDelivery {
    private final JavaMailSender sender;
    private final String from;
    private final URI acceptancePage;

    public SmtpAdminInvitationDelivery(JavaMailSender sender,String from,URI acceptancePage) {
        this.sender=sender; this.from=from; this.acceptancePage=acceptancePage;
    }

    @Override public void send(Message invitation) {
        if(TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Entrega de convite exige transação já encerrada");
        // Token Base64URL validado antes de compor um link controlado pelo servidor.
        if(invitation.token()==null || !invitation.token().matches("[A-Za-z0-9_-]{43}"))
            throw new IllegalArgumentException("Segredo de convite inválido");
        String link=acceptancePage.toASCIIString()+"#token="+invitation.token();
        var mail=new SimpleMailMessage();
        mail.setFrom(from); mail.setTo(invitation.email());
        mail.setSubject("OLIUS — Convite para administração");
        mail.setText("""
                Olá, %s!

                Você recebeu um convite para administrar o OLIUS.
                Para aceitar e definir sua própria senha, abra:

                %s

                O convite tem validade de 24 horas a partir da emissão.
                Um novo convite para o mesmo e-mail invalida o anterior.
                Depois do aceite, faça login normalmente.

                Não compartilhe este link. Se não esperava o convite, ignore a mensagem.
                A equipe OLIUS não solicita sua senha por e-mail.
                """.formatted(invitation.name(),link));
        sender.send(mail);
    }
}
