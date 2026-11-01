package br.com.olius.olius_core_api.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("olius.password-reset")
public record PasswordResetProperties(boolean enabled, String url, String from,
        @DefaultValue("30m") Duration ttl) { }
