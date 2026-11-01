package br.com.olius.olius_core_api.service;

import br.com.olius.olius_core_api.config.PasswordResetProperties;
import br.com.olius.olius_core_api.security.SecretTokens;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** Nenhum e-mail, hash, link, token ou texto de exceção entra no diagnóstico. */
@Component
public class PasswordResetWorker {
    private static final Logger LOG=LoggerFactory.getLogger(PasswordResetWorker.class);
    private final PasswordResetTransactions transactions;
    private final SecretTokens secrets;
    private final PasswordResetProperties properties;
    private final ObjectProvider<PasswordResetDelivery> deliveries;
    public PasswordResetWorker(PasswordResetTransactions transactions,SecretTokens secrets,
            PasswordResetProperties properties,ObjectProvider<PasswordResetDelivery> deliveries) {
        this.transactions=transactions; this.secrets=secrets; this.properties=properties; this.deliveries=deliveries;
    }
    public void process(String email) {
        try {
            String token=secrets.generate();
            var issued=transactions.issue(email,secrets.hash(token),properties.ttl());
            switch(issued.code()) {
                case "OK" -> deliveries.getObject().send(new PasswordResetDelivery.Message(email,token,issued.expiresAt()));
                case "USER_UNAVAILABLE", "RATE_LIMITED" -> { /* Mesma resposta pública; nada a entregar. */ }
                default -> LOG.error("Emissão de recuperação recusada por inconsistência interna");
            }
        } catch(RuntimeException failure) {
            // A emissão já confirmada não é desfeita por uma falha SMTP. Sem retry oculto.
            LOG.error("Falha no processamento de recuperação; tipo={}",failure.getClass().getSimpleName());
        }
    }
}
