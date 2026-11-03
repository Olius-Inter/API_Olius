package br.com.olius.olius_core_api.service;
import br.com.olius.olius_core_api.repository.AuthSessionRepository;
import br.com.olius.olius_core_api.repository.AuthSessionRepository.Outcome;
import java.util.UUID;
import br.com.olius.olius_core_api.repository.CredentialUpgradeRepository;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
/** Retorna códigos; não lança rejeição de login dentro da transação de revogação. */
@Service
public class SessionTransactions {
    private final AuthSessionRepository sessions;
    private final CredentialUpgradeRepository credentials;
    private final AuditContext audit;
    public SessionTransactions(AuthSessionRepository sessions,
        CredentialUpgradeRepository credentials, AuditContext audit) {
        this.sessions=sessions; this.credentials=credentials; this.audit=audit;
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,
        isolation=Isolation.READ_COMMITTED)
    public Outcome openWithCredentialUpgrade(UUID userId, byte[] tokenHash, String validatedHash, String replacement) {
        audit.user(userId,"Atualização do formato de senha após autenticação legada");
        if (!credentials.replace(userId,validatedHash,replacement)) throw invalidCredentials();
        // A substituição foi feita por esta transação após comprovar o hash antigo; não reler outro hash.
        Outcome outcome=sessions.open(userId,tokenHash,replacement);
        if (!"OK".equals(outcome.code())) throw invalidCredentials();
        return outcome;
    }
    private static BadCredentialsException invalidCredentials() {
        return new BadCredentialsException("Credenciais inválidas");
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public Outcome open(UUID userId,byte[] hash,String validatedHash) { return sessions.open(userId,hash,validatedHash); }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public Outcome rotate(byte[] current,byte[] next) { return sessions.rotate(current,next); }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void logoutRefresh(byte[] current,byte[] unusedSuccessor) {
        // Validate possession through the existing restricted database contract.
        // Rotation and revocation commit together; no successor leaves this transaction.
        Outcome outcome=sessions.rotate(current,unusedSuccessor);
        if("OK".equals(outcome.code())) sessions.revoke(outcome.userId(),outcome.sessionId());
        // REFRESH_REUSED must commit its defensive revocation too.
    }
    @Transactional
    public void logout(UUID userId,UUID sessionId) { sessions.revoke(userId,sessionId); }
    @Transactional
    public void logoutAll(UUID userId) { sessions.revokeAll(userId); }
}
