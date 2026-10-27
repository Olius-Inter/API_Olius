package br.com.olius.olius_core_api.config;

import br.com.olius.olius_core_api.service.StripePaymentEventProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name="olius.payments.stripe.enabled",havingValue="true")
public class StripeWebhookWorker {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(StripeWebhookWorker.class);
    private final StripePaymentEventProcessor processor;
    public StripeWebhookWorker(StripePaymentEventProcessor processor) { this.processor=processor; }
    @Scheduled(fixedDelayString="${olius.payments.stripe.webhook-poll-delay:5000}")
    public void process() {
        try { processor.processDue(); }
        catch(RuntimeException ex) {
            LOG.error("Falha temporária no processador Stripe exceptionType={}",ex.getClass().getName());
        }
    }
}
