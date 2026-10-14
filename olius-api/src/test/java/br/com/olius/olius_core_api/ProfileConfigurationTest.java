package br.com.olius.olius_core_api;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import static org.assertj.core.api.Assertions.assertThat;

/** Loads actual profile files without database or application infrastructure. */
class ProfileConfigurationTest {
    @TestConfiguration(proxyBeanMethods = false)
    static class EmptyConfiguration { }

    private SpringApplicationBuilder application() {
        return new SpringApplicationBuilder(EmptyConfiguration.class)
            .web(WebApplicationType.NONE)
            .properties("spring.config.location=classpath:/application.properties",
                "spring.main.banner-mode=off");
    }

    @Test
    void localUsesDevelopmentConnectionAndLocalSecurity() {
        try (var context = application().run("--spring.profiles.active=local",
            "--CONTAINER_DEV_URL=jdbc:postgresql://localhost:5433/profile_test",
            "--CONTAINER_DEV_USER=limited_local", "--CONTAINER_DEV_PASSWORD=fake-local")) {
            var env = context.getEnvironment();
            assertThat(env.getProperty("spring.datasource.url")).endsWith("/profile_test");
            assertThat(env.getProperty("spring.datasource.username")).isEqualTo("limited_local");
            assertThat(env.getProperty("olius.security.ephemeral-keys", Boolean.class)).isTrue();
            assertThat(env.getProperty("olius.security.cookie-secure", Boolean.class)).isFalse();
            assertThat(env.getProperty("springdoc.api-docs.enabled", Boolean.class)).isTrue();
        }
    }

    @Test
    void productionUsesProductionConnectionAndPersistentSecureDefaults() {
        try (var context = application().run("--spring.profiles.active=prod",
            "--DB_URL=jdbc:postgresql://production.invalid/profile_prod",
            "--DB_USER=limited_prod", "--DB_PASSWORD=fake-prod",
            "--CONTAINER_DEV_URL=jdbc:postgresql://localhost/never_use",
            "--OLIUS_JWT_EPHEMERAL_KEYS=true", "--OLIUS_COOKIE_SECURE=false")) {
            var env = context.getEnvironment();
            assertThat(env.getProperty("spring.datasource.url")).endsWith("/profile_prod");
            assertThat(env.getProperty("spring.datasource.username")).isEqualTo("limited_prod");
            assertThat(env.getProperty("spring.config.import")).isNull();
            assertThat(env.getProperty("olius.security.ephemeral-keys", Boolean.class)).isFalse();
            assertThat(env.getProperty("olius.security.cookie-secure", Boolean.class)).isTrue();
        }
    }

    @Test
    void commonConfigurationDoesNotSelectADatabaseOrImportLocalSecrets() {
        try (var context = application().run("--spring.profiles.active=profile-check")) {
            var env = context.getEnvironment();
            assertThat(env.getProperty("spring.datasource.url")).isNull();
            assertThat(env.getProperty("spring.config.import")).isNull();
            assertThat(env.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        }
    }
}
