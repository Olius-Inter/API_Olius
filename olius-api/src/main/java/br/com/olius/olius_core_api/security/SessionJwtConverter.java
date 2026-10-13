package br.com.olius.olius_core_api.security;
import br.com.olius.olius_core_api.repository.AuthSessionRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.*;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
@Component
public class SessionJwtConverter implements Converter<Jwt,AbstractAuthenticationToken> {
    private final AuthSessionRepository sessions;
    public SessionJwtConverter(AuthSessionRepository sessions) { this.sessions=sessions; }
    @Override public AbstractAuthenticationToken convert(Jwt jwt) {
        UUID userId,sessionId;
        try { userId=UUID.fromString(jwt.getSubject()); sessionId=UUID.fromString(jwt.getClaimAsString("sid")); }
        catch(IllegalArgumentException | NullPointerException invalid) { throw new BadCredentialsException("Claims inválidas"); }
        var state=sessions.status(userId,sessionId);
        if(!state.valid()) throw new BadCredentialsException("Sessão indisponível");
        // Perfil atual vem do PostgreSQL, não de uma role livremente recebida do cliente.
        return new JwtAuthenticationToken(jwt,List.of(new SimpleGrantedAuthority("ROLE_"+state.type().name())));
    }
}
