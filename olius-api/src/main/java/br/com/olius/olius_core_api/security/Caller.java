package br.com.olius.olius_core_api.security;
import br.com.olius.olius_core_api.entity.UserType;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
public final class Caller {
    private Caller() { }
    public static UUID id(Authentication auth) { return UUID.fromString(((JwtAuthenticationToken)auth).getToken().getSubject()); }
    public static UUID session(Authentication auth) { return UUID.fromString(((JwtAuthenticationToken)auth).getToken().getClaimAsString("sid")); }
    public static UserType type(Authentication auth) {
        return auth.getAuthorities().stream().map(a -> a.getAuthority()).filter(a -> a.startsWith("ROLE_"))
            .map(a -> UserType.valueOf(a.substring(5))).findFirst().orElseThrow();
    }
}
