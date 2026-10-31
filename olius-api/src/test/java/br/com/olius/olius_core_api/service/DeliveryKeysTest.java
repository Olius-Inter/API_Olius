package br.com.olius.olius_core_api.service;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DeliveryKeysTest {
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
