package br.com.olius.olius_core_api.config;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class StripeSchedulingConfigurationTest {
    @Test void aBlockedFinancialTaskDoesNotPreventTheOtherWorkerFromStarting() throws Exception {
        var scheduler=new StripeSchedulingConfiguration().taskScheduler();
        var entered=new CountDownLatch(3);
        var release=new CountDownLatch(1);
        Runnable task=()->{
            entered.countDown();
            try { release.await(); }
            catch(InterruptedException failure) { Thread.currentThread().interrupt(); }
        };
        scheduler.initialize();
        try {
            scheduler.schedule(task,Instant.now());scheduler.schedule(task,Instant.now());scheduler.schedule(task,Instant.now());
            assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
        } finally { release.countDown();scheduler.shutdown(); }
    }
}
