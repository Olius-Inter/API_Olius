package br.com.olius.olius_core_api;

import br.com.olius.olius_core_api.config.AdminInvitationEmailConfiguration;
import br.com.olius.olius_core_api.service.AdminInvitationDelivery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mail.javamail.JavaMailSender;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class AdminInvitationEmailConfigurationTest {
    private final ApplicationContextRunner runner=new ApplicationContextRunner()
            .withUserConfiguration(AdminInvitationEmailConfiguration.class)
            .withBean(JavaMailSender.class,() -> mock(JavaMailSender.class));

    private ApplicationContextRunner configured() {
        return runner.withPropertyValues("olius.admin-invitations.email.enabled=true",
                "olius.admin-invitations.email.from=olius@example.invalid",
                "olius.admin-invitations.email.acceptance-url=https://front.example.invalid/aceite",
                "spring.mail.username=olius@example.invalid","spring.mail.password=fixture-only");
    }

    @Test void disabledChannelDoesNotRequireCredentialsOrCreateDelivery() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(AdminInvitationDelivery.class);
        });
    }
    @Test void enabledAndConfiguredChannelCreatesDelivery() {
        configured().run(context -> assertThat(context).hasSingleBean(AdminInvitationDelivery.class));
    }
    @ParameterizedTest @ValueSource(strings={"spring.mail.username","spring.mail.password",
            "olius.admin-invitations.email.from","olius.admin-invitations.email.acceptance-url"})
    void missingConfigurationFailsBeforeServingRequests(String property) {
        configured().withPropertyValues(property+"=").run(context -> assertThat(context).hasFailed());
    }
    @ParameterizedTest @ValueSource(strings={"https://user:pass@example.invalid/aceite",
            "https://example.invalid/aceite?token=wrong","https://example.invalid/aceite#wrong",
            "http://public.example.invalid/aceite","javascript:alert(1)","/relative","https://"})
    void rejectsUnsafeOrAmbiguousAcceptanceAddress(String url) {
        configured().withPropertyValues("olius.admin-invitations.email.acceptance-url="+url)
                .run(context -> assertThat(context).hasFailed());
    }
    @Test void acceptsLoopbackHttpOnlyOutsideProduction() {
        configured().withPropertyValues("olius.admin-invitations.email.acceptance-url=http://localhost:3000/aceite")
                .run(context -> assertThat(context).hasSingleBean(AdminInvitationDelivery.class));
        configured().withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
                .withPropertyValues("olius.admin-invitations.email.acceptance-url=http://localhost:3000/aceite")
                .run(context -> assertThat(context).hasFailed());
    }
    @Test void invalidSenderIsRejected() {
        configured().withPropertyValues("olius.admin-invitations.email.from=invalid")
                .run(context -> assertThat(context).hasFailed());
    }
}
