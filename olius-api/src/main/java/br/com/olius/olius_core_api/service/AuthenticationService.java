package br.com.olius.olius_core_api.service;
import br.com.olius.olius_core_api.config.SecurityProperties;
import br.com.olius.olius_core_api.dto.IdentityRequests.Login;
import br.com.olius.olius_core_api.dto.IdentityResponses.Access;
import br.com.olius.olius_core_api.entity.enums.UserStatus;
import br.com.olius.olius_core_api.repository.AuthSessionRepository.Outcome;
import br.com.olius.olius_core_api.repository.UserRepository;
import br.com.olius.olius_core_api.security.SecretTokens;
import java.time.Instant;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;

@Service
public class AuthenticationService {
    public record Tokens(Access access,String refresh,Instant refreshExpiresAt) {
        @Override public String toString() { return "Tokens[redacted]"; }
    }
    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final SessionTransactions sessions;
    private final SecretTokens secrets;
    private final JwtEncoder encoder;
    private final SecurityProperties properties;
    private final String dummyHash;
    public AuthenticationService(UserRepository users,PasswordEncoder passwords,SessionTransactions sessions,
        SecretTokens secrets,JwtEncoder encoder,SecurityProperties properties) {
        this.users=users; this.passwords=passwords; this.sessions=sessions; this.secrets=secrets;
        this.encoder=encoder; this.properties=properties; dummyHash=passwords.encode(secrets.generate());
    }
    public Tokens login(Login request) {
        var user=users.findByEmail(EmailAddress.normalize(request.email()));
        boolean matches=passwords.matches(request.password(),user.map(u -> u.getPasswordHash()).orElse(dummyHash));
        if(!matches || user.isEmpty() || user.get().getStatus()!=UserStatus.ACTIVE)
            throw new BadCredentialsException("Credenciais inválidas");
        String refresh=secrets.generate();
        Outcome outcome=sessions.open(user.get().getId(),secrets.hash(refresh));
        return issue(outcome,refresh);
    }
    public Tokens refresh(String current) {
        if(!secrets.valid(current)) throw new BadCredentialsException("Token inválido");
        String next=secrets.generate();
        // REQUIRES_NEW termina (incluindo commit de REFRESH_REUSED) antes de issue rejeitar o resultado.
        Outcome outcome=sessions.rotate(secrets.hash(current),secrets.hash(next));
        return issue(outcome,next);
    }
    public void logoutWeb(String refresh) {
        // Idempotent for absent, malformed, expired or already revoked cookies.
        if(secrets.valid(refresh)) sessions.logoutRefresh(secrets.hash(refresh),secrets.hash(secrets.generate()));
    }
    private Tokens issue(Outcome outcome,String refresh) {
        if(!"OK".equals(outcome.code())) throw new BadCredentialsException("Sessão inválida");
        Instant now=Instant.now();
        Instant expiry=now.plusSeconds(900);
        JwtClaimsSet claims=JwtClaimsSet.builder().issuer(properties.getIssuer())
            .audience(java.util.List.of(properties.getAudience())).subject(outcome.userId().toString())
            .id(java.util.UUID.randomUUID().toString()).issuedAt(now).expiresAt(expiry)
            .claim("sid",outcome.sessionId().toString()).build();
        String access=encoder.encode(JwtEncoderParameters.from(
            JwsHeader.with(SignatureAlgorithm.RS256).keyId(properties.getKeyId()).build(),claims)).getTokenValue();
        return new Tokens(new Access(access,"Bearer",900,expiry),refresh,outcome.expiresAt());
    }
}
