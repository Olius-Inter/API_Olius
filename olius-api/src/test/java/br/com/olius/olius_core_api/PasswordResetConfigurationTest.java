package br.com.olius.olius_core_api;

import br.com.olius.olius_core_api.config.PasswordResetConfiguration;
import br.com.olius.olius_core_api.service.PasswordResetDelivery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mail.javamail.JavaMailSender;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class PasswordResetConfigurationTest {
    private final ApplicationContextRunner runner=new ApplicationContextRunner()
        .withUserConfiguration(PasswordResetConfiguration.class)
        .withBean(JavaMailSender.class,() -> mock(JavaMailSender.class));
    private ApplicationContextRunner enabled() {
        return runner.withPropertyValues("olius.password-reset.enabled=true","olius.password-reset.from=olius@example.invalid",
            "olius.password-reset.url=https://front.example.invalid/reset","spring.mail.username=olius@example.invalid",
            "spring.mail.password=fixture-only");
    }
    @Test void disabledDoesNotCreateDeliveryOrExecutor() {
        runner.run(context -> { assertThat(context).hasNotFailed(); assertThat(context).doesNotHaveBean(PasswordResetDelivery.class);
            assertThat(context).doesNotHaveBean("passwordResetExecutor"); });
    }
    @Test void enabledCreatesIndependentChannelAndBoundedQueue() {
        enabled().withPropertyValues("olius.admin-invitations.email.enabled=false").run(context -> {
            assertThat(context).hasNotFailed(); assertThat(context).hasSingleBean(PasswordResetDelivery.class);
            var executor=context.getBean("passwordResetExecutor",org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor.class);
            assertThat(executor.getMaxPoolSize()).isEqualTo(2);
            assertThat(executor.getQueueCapacity()).isEqualTo(100);
        });
    }
    @ParameterizedTest @ValueSource(strings={"spring.mail.username","spring.mail.password","olius.password-reset.from","olius.password-reset.url"})
    void missingRequiredValuePreventsEnabledStartup(String property) {
        enabled().withPropertyValues(property+"=").run(context -> assertThat(context).hasFailed());
    }
    @ParameterizedTest @ValueSource(strings={"http://public.example.invalid/reset","https://front.example.invalid/reset?token=x",
        "https://front.example.invalid/reset#token=x","https://user:pass@front.example.invalid/reset","javascript:alert(1)"})
    void refusesUntrustedPage(String url) { enabled().withPropertyValues("olius.password-reset.url="+url).run(context -> assertThat(context).hasFailed()); }
    @Test void localHttpOnlyOutsideProduction() {
        enabled().withPropertyValues("olius.password-reset.url=http://localhost:3000/reset").run(context -> assertThat(context).hasNotFailed());
        enabled().withPropertyValues("spring.profiles.active=prod","olius.password-reset.url=http://localhost:3000/reset").run(context -> assertThat(context).hasFailed());
    }
    @ParameterizedTest @ValueSource(strings={"0s","-1s"})
    void refusesNonpositiveTtl(String ttl) { enabled().withPropertyValues("olius.password-reset.ttl="+ttl).run(context -> assertThat(context).hasFailed()); }
}
