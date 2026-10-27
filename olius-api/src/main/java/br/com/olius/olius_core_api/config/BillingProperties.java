package br.com.olius.olius_core_api.config;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties("olius.payments")
@Getter @Setter
public class BillingProperties {
    private Duration initialCheckoutTtl=Duration.ofHours(1);
    private Duration renewalCheckoutTtl=Duration.ofHours(1);
}
