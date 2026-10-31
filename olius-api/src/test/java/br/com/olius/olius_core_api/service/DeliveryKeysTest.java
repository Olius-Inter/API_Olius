package br.com.olius.olius_core_api.service;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DeliveryKeysTest {
    @Test void preservesThePublishedDerivationAcrossDeployments() {
        assertThat(DeliveryKeys.internal(
            UUID.fromString("11111111-1111-1111-1111-111111111111"),
            UUID.fromString("22222222-2222-2222-2222-222222222222")))
            .isEqualTo(UUID.fromString("587f561d-4d25-814a-b8c3-6aaa1084b7cf"));
    }
    @Test void scopesTheSameClientKeyToTheAuthenticatedValidator() {
        UUID validator=UUID.randomUUID();
        UUID other=UUID.randomUUID();
        UUID clientKey=UUID.randomUUID();
        UUID internal=DeliveryKeys.internal(validator,clientKey);
        assertThat(DeliveryKeys.internal(validator,clientKey)).isEqualTo(internal);
        assertThat(DeliveryKeys.internal(other,clientKey)).isNotEqualTo(internal);
        assertThat(DeliveryKeys.internal(validator,UUID.randomUUID())).isNotEqualTo(internal);
        assertThat(internal).isNotEqualTo(clientKey);
        assertThat(internal.version()).isEqualTo(8);
    }
}
