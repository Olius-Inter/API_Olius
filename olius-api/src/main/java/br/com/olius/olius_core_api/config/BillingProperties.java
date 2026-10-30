package br.com.olius.olius_core_api.config;

import java.time.Duration;
import jakarta.validation.constraints.AssertTrue;
import org.springframework.validation.annotation.Validated;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Prazos técnicos de Checkout; configurações inválidas são recusadas durante o boot. */
@Configuration
@ConfigurationProperties("olius.payments")
@Validated
@Getter @Setter
public class BillingProperties {
    public static final Duration UPGRADE_MINIMUM_WINDOW=Duration.ofMinutes(35);
    private Duration initialCheckoutTtl=Duration.ofHours(1);
    private Duration renewalCheckoutTtl=Duration.ofHours(1);
    private Duration upgradeCheckoutTtl=Duration.ofHours(1);
    /** Exige a margem de emissão do upgrade, sem ultrapassar o teto aprovado de uma hora. */
    @AssertTrue(message="O prazo de upgrade deve superar 35 minutos e não ultrapassar uma hora")
    public boolean isUpgradeCheckoutTtlValid() {
        return upgradeCheckoutTtl!=null && upgradeCheckoutTtl.compareTo(UPGRADE_MINIMUM_WINDOW)>0
            && upgradeCheckoutTtl.compareTo(Duration.ofHours(1))<=0;
    }
}
