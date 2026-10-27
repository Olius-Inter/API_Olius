package br.com.olius.olius_core_api.config;

import br.com.olius.olius_core_api.integration.payment.StripeCheckoutGateway;
import java.net.URI;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(StripeProperties.class)
public class StripeConfiguration {
    @Bean
    @ConditionalOnProperty(name="olius.payments.stripe.enabled", havingValue="true")
    StripeCheckoutGateway stripeCheckoutGateway(StripeProperties properties) {
        if (properties.getSecretKey() == null || !properties.getSecretKey().startsWith("sk_test_"))
            throw new IllegalStateException("Esta etapa Stripe exige chave de sandbox");
        return new StripeCheckoutGateway(properties.getSecretKey(),
                returnUrl(properties.getSuccessUrl()), returnUrl(properties.getCancelUrl()));
    }

    @Bean
    @ConditionalOnProperty(name="olius.payments.stripe.enabled", havingValue="true")
    br.com.olius.olius_core_api.security.StripeWebhookSignatureVerifier stripeWebhookVerifier(StripeProperties properties) {
        return new br.com.olius.olius_core_api.security.StripeWebhookSignatureVerifier(
                properties.getWebhookSecret(),java.time.Clock.systemUTC());
    }

    private URI returnUrl(String value) {
        if (value == null || value.isBlank())
            throw new IllegalStateException("Configure as URLs de retorno do Checkout");
        URI uri=URI.create(value);
        boolean https="https".equals(uri.getScheme());
        boolean local="http".equals(uri.getScheme()) && "localhost".equals(uri.getHost());
        if ((!https && !local) || uri.getHost()==null || uri.getUserInfo()!=null || uri.getFragment()!=null)
            throw new IllegalStateException("URL de retorno inválida");
        return uri;
    }
}
