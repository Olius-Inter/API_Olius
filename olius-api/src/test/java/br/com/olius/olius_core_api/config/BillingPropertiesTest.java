package br.com.olius.olius_core_api.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;

class BillingPropertiesTest {
    private final ApplicationContextRunner context=new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class,ValidationAutoConfiguration.class))
        .withUserConfiguration(BillingProperties.class);
    @Test void defaultConfigurationStartsWithoutStripeCredentials() {
        context.run(application->assertThat(application).hasNotFailed());
    }
    @ParameterizedTest @ValueSource(strings={"36m","1h"})
    void acceptsApprovedUpgradeWindow(String ttl) {
        context.withPropertyValues("olius.payments.upgrade-checkout-ttl="+ttl).run(application->assertThat(application).hasNotFailed());
    }
    @ParameterizedTest @ValueSource(strings={"0m","-1m","30m","35m","61m","2h","invalid"})
    void rejectsInvalidUpgradeWindowDuringStartup(String ttl) {
        context.withPropertyValues("olius.payments.upgrade-checkout-ttl="+ttl).run(application->assertThat(application).hasFailed());
    }
}
