package br.com.olius.olius_core_api.config;

import br.com.olius.olius_core_api.service.StripeChargeReconciler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name="olius.payments.stripe.enabled",havingValue="true")
public class StripeChargeReconciliationWorker {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(StripeChargeReconciliationWorker.class);
    private final StripeChargeReconciler reconciler;
    public StripeChargeReconciliationWorker(StripeChargeReconciler reconciler) { this.reconciler=reconciler; }
    @Scheduled(fixedDelay=60000)
    public void process() {
        try { reconciler.processDue(); }
        catch(RuntimeException failure) { LOG.error("Reconciliação Stripe indisponível exceptionType={}",failure.getClass().getName()); }
    }
}
