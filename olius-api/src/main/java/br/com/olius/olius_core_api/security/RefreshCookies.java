package br.com.olius.olius_core_api.security;
import br.com.olius.olius_core_api.config.SecurityProperties;
import java.time.*;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
@Component
public class RefreshCookies {
    public static final String NAME="olius_refresh";
    private final SecurityProperties properties;
    public RefreshCookies(SecurityProperties properties) { this.properties=properties; }
    public String issue(String token,Instant expiry) { return build(token,Duration.between(Instant.now(),expiry)).toString(); }
    public String clear() { return build("",Duration.ZERO).toString(); }
    private ResponseCookie build(String value,Duration maxAge) {
        return ResponseCookie.from(NAME,value).httpOnly(true).secure(properties.isCookieSecure())
            .sameSite(properties.getCookieSameSite()).path("/api/v1/auth/web").maxAge(maxAge).build();
    }
}
