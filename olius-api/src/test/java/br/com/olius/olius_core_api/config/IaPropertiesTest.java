package br.com.olius.olius_core_api.config;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class IaPropertiesTest {
    @ParameterizedTest
    @ValueSource(strings = {"file:///tmp/api", "http://user:secret@ia", "http://ia/path", "http://ia?token=x", "http://ia#fragment",
        "//ia", "ia", "ia:8000/"})
    void rejectsAmbiguousOrCredentialBearingOrigins(String value) {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new IaProperties(true, URI.create(value), Duration.ofSeconds(1), Duration.ofSeconds(25)))
                .withMessageContaining("OLIUS_IA_BASE_URL");
    }
    @Test void rejectsInvalidTimeouts() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new IaProperties(true, URI.create("http://ia"), Duration.ZERO, Duration.ofSeconds(25)));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new IaProperties(true, URI.create("http://ia"), Duration.ofSeconds(3), Duration.ofSeconds(1)));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new IaProperties(true, URI.create("http://ia"), Duration.ofSeconds(3), Duration.ofSeconds(121)));
    }
}
