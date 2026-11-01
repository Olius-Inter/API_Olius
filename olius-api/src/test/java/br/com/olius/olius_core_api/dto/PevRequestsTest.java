package br.com.olius.olius_core_api.dto;

import jakarta.validation.Validation;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PevRequestsTest {
    @Test void addressUpdateRequiresCompletePostalDataAndValidCoordinates() {
        try(var factory=Validation.buildDefaultValidatorFactory()) {
            var validator=factory.getValidator();
            assertThat(validator.validate(new PevRequests.UpdateAddress(null)))
                .extracting(v->v.getPropertyPath().toString()).containsExactly("address");
            var valid=new IdentityRequests.AddressInput("SP","Cidade","Bairro","Rua","10","01001000",null,null,null);
            assertThat(validator.validate(new PevRequests.UpdateAddress(valid))).isEmpty();
            var invalid=new IdentityRequests.AddressInput("sp","","Bairro","Rua","10","01001-000",null,
                new BigDecimal("90.01"),new BigDecimal("-180.01"));
            assertThat(validator.validate(new PevRequests.UpdateAddress(invalid)))
                .extracting(v->v.getPropertyPath().toString())
                .containsExactlyInAnyOrder("address.state","address.city","address.cep","address.latitude","address.longitude");
            var boundary=new IdentityRequests.AddressInput("SP","Cidade","Bairro","Rua","10","01001000",null,
                new BigDecimal("-90"),new BigDecimal("180"));
            assertThat(validator.validate(new PevRequests.UpdateAddress(boundary))).isEmpty();
        }
    }
}
