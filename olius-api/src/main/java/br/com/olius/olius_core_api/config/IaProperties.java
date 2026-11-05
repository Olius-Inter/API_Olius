package br.com.olius.olius_core_api.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("olius.ia")
public record IaProperties(boolean enabled, URI baseUrl, Duration connectTimeout, Duration requestTimeout) {
    public IaProperties {
        if (baseUrl == null || baseUrl.getScheme() == null
                || !java.util.Set.of("http", "https").contains(baseUrl.getScheme())
                || baseUrl.getHost() == null || baseUrl.getRawUserInfo() != null
                || baseUrl.getRawQuery() != null || baseUrl.getRawFragment() != null
                || !(baseUrl.getPath().isEmpty() || baseUrl.getPath().equals("/"))) {
            throw new IllegalArgumentException("OLIUS_IA_BASE_URL deve conter apenas a origem HTTP(S), sem credenciais.");
        }
        if (connectTimeout == null || requestTimeout == null
                || connectTimeout.compareTo(Duration.ofMillis(100)) < 0
                || requestTimeout.compareTo(connectTimeout) < 0
                || requestTimeout.compareTo(Duration.ofSeconds(120)) > 0) {
            throw new IllegalArgumentException("Timeouts da IA inválidos: conexão >= 100ms, total >= conexão e <= 120s.");
        }
    }
}
