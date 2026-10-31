package br.com.olius.olius_core_api.dto;

import br.com.olius.olius_core_api.entity.enums.RecordStatus;
import jakarta.validation.Validation;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DeliveryCorrectionContractsTest {
    @Test void validatesStatusDependentFactsAndRedactsJustification() {
        try(var factory=Validation.buildDefaultValidatorFactory()) {
            var validator=factory.getValidator();
            var date=Instant.now().minusSeconds(60);
            var valid=new DeliveryCorrectionContracts.Correction(1,RecordStatus.RECORDED,BigDecimal.ONE,date,"private reason");
            assertThat(validator.validate(valid)).isEmpty();
            assertThat(valid.toString()).doesNotContain("private reason");
            assertThat(validator.validate(new DeliveryCorrectionContracts.Correction(1,RecordStatus.ANNULLED,null,null,"Anular"))).isEmpty();
            for(var status:RecordStatus.values())
                for(var volume:new BigDecimal[]{null,BigDecimal.ONE})
                    for(var time:new Instant[]{null,date}) {
                        var input=new DeliveryCorrectionContracts.Correction(1,status,volume,time,"Conferência");
                        boolean expected=status==RecordStatus.ANNULLED ? volume==null && time==null : volume!=null && time!=null;
                        assertThat(input.isStatusPayloadValid()).isEqualTo(expected);
                    }
            assertThat(validator.validate(new DeliveryCorrectionContracts.Correction(1,null,null,null,"Conferência")))
                .extracting(v->v.getPropertyPath().toString()).contains("recordStatus");
        }
    }
}
