package br.com.olius.olius_core_api.config;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
@ConfigurationProperties("olius.security") @Getter @Setter
public class SecurityProperties {
    private int authRequestsPerMinute=60;
    private String issuer="olius-core-api";
    private String audience="olius-apis";
    private String keyId="olius-1";
    private String privateKeyPath;
    private String publicKeyPath;
    private boolean ephemeralKeys=false;
    private boolean cookieSecure=true;
    private String cookieSameSite="Strict";
    private List<String> allowedOrigins=List.of();
}
