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
    public Outcome open(UUID userId,byte[] hash) { return sessions.open(userId,hash); }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public Outcome rotate(byte[] current,byte[] next) { return sessions.rotate(current,next); }
    @Transactional
    public void logout(UUID userId,UUID sessionId) { sessions.revoke(userId,sessionId); }
    @Transactional
    public void logoutAll(UUID userId) { sessions.revokeAll(userId); }
}
