package br.com.olius.olius_core_api.service;

import br.com.olius.olius_core_api.repository.PasswordResetRepository;
import br.com.olius.olius_core_api.repository.PasswordResetRepository.Issued;
import java.time.Duration;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PasswordResetTransactions {
    private final PasswordResetRepository repository;
    public PasswordResetTransactions(PasswordResetRepository repository) { this.repository=repository; }
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED)
    public Issued issue(String email,byte[] hash,Duration ttl) { return repository.issue(email,hash,ttl); }
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED)
    public String complete(byte[] tokenHash,String passwordHash) { return repository.complete(tokenHash,passwordHash); }
}
