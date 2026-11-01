package br.com.olius.olius_core_api.service;
import br.com.olius.olius_core_api.repository.AuthSessionRepository;
import br.com.olius.olius_core_api.repository.AuthSessionRepository.Outcome;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
/** Retorna códigos; não lança rejeição de login dentro da transação de revogação. */
@Service
public class SessionTransactions {
    private final AuthSessionRepository sessions;
    public SessionTransactions(AuthSessionRepository sessions) { this.sessions=sessions; }
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
