package br.com.olius.olius_core_api.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("olius.payments.stripe")
@Getter @Setter
public class StripeProperties {
    private boolean enabled;
    private String secretKey;
    private String webhookSecret;
    private String successUrl;
    private String cancelUrl;
}
