package br.com.olius.olius_core_api.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Permite executar pagamentos, reembolsos e reconciliação sem bloqueio mútuo. */
@Configuration
@ConditionalOnProperty(name="olius.payments.stripe.enabled",havingValue="true")
public class StripeSchedulingConfiguration {
    @Bean
    ThreadPoolTaskScheduler taskScheduler() {
        var scheduler=new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(3);
        scheduler.setThreadNamePrefix("olius-stripe-");
        return scheduler;
    }
}
