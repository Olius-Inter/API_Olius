package br.com.olius.olius_core_api.service;

import br.com.olius.olius_core_api.config.PasswordResetProperties;
import br.com.olius.olius_core_api.dto.PasswordResetRequests;
import br.com.olius.olius_core_api.exception.PasswordResetInvalidException;
import br.com.olius.olius_core_api.security.SecretTokens;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.util.concurrent.RejectedExecutionException;

@Service
public class PasswordResetService {
    private final PasswordResetProperties properties;
    private final ObjectProvider<ThreadPoolTaskExecutor> executors;
    private final PasswordResetWorker worker;
    private final PasswordResetTransactions transactions;
    private final SecretTokens secrets;
    private final PasswordEncoder passwords;
    public PasswordResetService(PasswordResetProperties properties,
            @Qualifier("passwordResetExecutor") ObjectProvider<ThreadPoolTaskExecutor> executors,
            PasswordResetWorker worker,PasswordResetTransactions transactions,SecretTokens secrets,PasswordEncoder passwords) {
        this.properties=properties; this.executors=executors; this.worker=worker;
        this.transactions=transactions; this.secrets=secrets; this.passwords=passwords;
    }
    @Transactional(propagation=Propagation.NEVER)
    public void request(PasswordResetRequests.Request request) {
        requireEnabled();
        var executor=executors.getIfAvailable();
        if(executor==null) throw unavailable();
        // Todas as solicitações válidas entram na mesma fila antes de consultar a conta.
        String email=EmailAddress.normalize(request.email());
        try { executor.execute(() -> worker.process(email)); }
        catch(RejectedExecutionException full) { throw unavailable(); }
    }
    @Transactional(propagation=Propagation.NEVER)
    public void complete(PasswordResetRequests.Complete request) {
        requireEnabled();
        if(!secrets.valid(request.token())) throw new PasswordResetInvalidException();
        String result=transactions.complete(secrets.hash(request.token()),passwords.encode(request.password()));
        switch(result) {
            case "OK" -> { /* Sem login automático ou segunda revogação fora da transação. */ }
            case "INVALID_TOKEN" -> throw new PasswordResetInvalidException();
            default -> throw new IllegalStateException("Resultado inesperado da recuperação");
        }
    }
    private void requireEnabled() { if(!properties.enabled()) throw unavailable(); }
    private static ResponseStatusException unavailable() { return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE); }
}
