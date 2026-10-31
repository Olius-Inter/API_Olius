package br.com.olius.olius_core_api.dto;

import jakarta.validation.Validation;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DeliveryContractsTest {
    @Test void validatesRecordBoundariesAndNeverPrintsTheQrToken() {
        try (var factory=Validation.buildDefaultValidatorFactory()) {
            var validator=factory.getValidator();
            UUID key=UUID.randomUUID();
            Instant date=Instant.now().minusSeconds(60);
            String qr="A".repeat(43);
            var valid=new DeliveryContracts.Record(key,qr,new BigDecimal("999.99"),date);
            assertThat(validator.validate(valid)).isEmpty();
            assertThat(valid.toString()).doesNotContain(qr);
            assertThat(validator.validate(new DeliveryContracts.Record(key,null,new BigDecimal("0.01"),date)))
                .isEmpty(); // Token omitido é permitido apenas para recuperação, validada no serviço.
            assertThat(validator.validate(new DeliveryContracts.Record(null,qr,BigDecimal.ONE,date)))
                .extracting(v -> v.getPropertyPath().toString()).contains("idempotencyKey");
            assertThat(validator.validate(new DeliveryContracts.Record(key,"invalid",BigDecimal.ONE,date)))
                .extracting(v -> v.getPropertyPath().toString()).contains("qrToken");
            for (String volume : new String[] {"0", "-1", "1.001", "1000.00"})
                assertThat(validator.validate(new DeliveryContracts.Record(key,qr,new BigDecimal(volume),date)))
                    .extracting(v -> v.getPropertyPath().toString()).contains("oilVolumeLiters");
            assertThat(validator.validate(new DeliveryContracts.Record(key,qr,BigDecimal.ONE,Instant.now().plusSeconds(3600))))
                .extracting(v -> v.getPropertyPath().toString()).contains("deliveryDate");
            assertThat(validator.validate(new DeliveryContracts.Record(key,qr,BigDecimal.ONE,null)))
                .extracting(v -> v.getPropertyPath().toString()).contains("deliveryDate");
        }
    }
}
