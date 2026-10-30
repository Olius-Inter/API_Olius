package br.com.olius.olius_core_api;

import br.com.olius.olius_core_api.dto.DriverContracts.*;
import br.com.olius.olius_core_api.entity.enums.UserStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class DriverContractsPrivacyTest {
    @Test void documentBearingRecordsDoNotExposeTheirContentsWhenFormatted() {
        String cpf="12345678901";
        String cnh="98765432109";
        for(Object dto:List.of(new Create("Pessoa",cpf,cnh),new Update("Pessoa",cpf,cnh),
                new View(UUID.randomUUID(),"Pessoa",cpf,cnh,UserStatus.ACTIVE,Instant.EPOCH,Instant.EPOCH)))
            assertThat(dto.toString()).contains("[redacted]").doesNotContain(cpf,cnh,"Pessoa");
    }
}
