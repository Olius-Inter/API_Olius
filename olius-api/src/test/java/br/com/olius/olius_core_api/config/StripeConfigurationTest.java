package br.com.olius.olius_core_api.config;

import br.com.olius.olius_core_api.integration.payment.CheckoutGateway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;

class StripeConfigurationTest {
    private final ApplicationContextRunner context=new ApplicationContextRunner()
            .withUserConfiguration(StripeConfiguration.class);

    @Test void disabledConnectorDoesNotRequireCredentialsOrUrls() {
        context.run(application -> {
            assertThat(application).hasNotFailed();
            assertThat(application).doesNotHaveBean(CheckoutGateway.class);
        });
    }
    @Test void completeSandboxConfigurationCreatesCheckoutVerificationAndSignatureBeans() {
        context.withPropertyValues("olius.payments.stripe.enabled=true",
                "olius.payments.stripe.secret-key=sk_test_fixture_only",
                "olius.payments.stripe.webhook-secret=whsec_fixture_only",
                "olius.payments.stripe.success-url=http://localhost:3000/success",
                "olius.payments.stripe.cancel-url=http://localhost:3000/cancel")
                .run(application -> {
                    assertThat(application).hasNotFailed();
                    assertThat(application).hasSingleBean(CheckoutGateway.class);
                    assertThat(application).hasSingleBean(br.com.olius.olius_core_api.integration.payment.PaymentVerificationGateway.class);
                    assertThat(application).hasSingleBean(br.com.olius.olius_core_api.security.StripeWebhookSignatureVerifier.class);
                });
    }

    @Test void enabledConnectorRejectsMissingOrLiveCredentials() {
        context.withPropertyValues("olius.payments.stripe.enabled=true")
                .run(application -> assertThat(application).hasFailed());
        context.withPropertyValues("olius.payments.stripe.enabled=true",
                "olius.payments.stripe.secret-key=sk_live_example")
                .run(application -> assertThat(application).hasFailed());
    }
    @Test void enabledConnectorRequiresBothConfiguredReturnUrls() {
        context.withPropertyValues("olius.payments.stripe.enabled=true",
                "olius.payments.stripe.secret-key=sk_test_example",
                "olius.payments.stripe.success-url=http://localhost:3000/success")
                .run(application -> assertThat(application).hasFailed());
    }
    @Test void rejectsInsecureRemoteReturnUrls() {
        context.withPropertyValues("olius.payments.stripe.enabled=true",
                "olius.payments.stripe.secret-key=sk_test_example",
                "olius.payments.stripe.success-url=http://remote.example/success",
                "olius.payments.stripe.cancel-url=http://localhost:3000/cancel")
                .run(application -> assertThat(application).hasFailed());
    }
}
