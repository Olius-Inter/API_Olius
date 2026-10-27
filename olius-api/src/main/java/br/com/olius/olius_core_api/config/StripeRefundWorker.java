package br.com.olius.olius_core_api.config;

import br.com.olius.olius_core_api.service.StripeRefundProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name="olius.payments.stripe.enabled",havingValue="true")
public class StripeRefundWorker {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(StripeRefundWorker.class);
    private final StripeRefundProcessor processor;
    public StripeRefundWorker(StripeRefundProcessor processor) { this.processor=processor; }
    @Scheduled(fixedDelay=5000)
    public void process() {
        try { processor.processDue(); }
        catch(RuntimeException failure) {
            LOG.error("Processador de reembolsos indisponível exceptionType={}",failure.getClass().getName());
        }
    }
}
