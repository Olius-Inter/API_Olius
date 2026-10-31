package br.com.olius.olius_core_api;

import br.com.olius.olius_core_api.dto.DeliveryCorrectionContracts.Correction;
import br.com.olius.olius_core_api.entity.enums.RecordStatus;
import br.com.olius.olius_core_api.repository.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.UncategorizedSQLException;
import java.sql.SQLException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class DeliveryCorrectionRepositoryTest {
    @Test void procedureFailureIsTranslatedWithoutReadingUncommittedResult() {
        var jdbc=mock(JdbcTemplate.class);
        var reads=mock(DeliveryAdministrationRepository.class);
        var errors=mock(DeliveryCorrectionErrors.class);
        var failure=new UncategorizedSQLException("correction",null,new SQLException("technical"));
        var translated=new IllegalStateException("translated");
        when(jdbc.update(anyString(),any(Object[].class))).thenThrow(failure);
        when(errors.translate(failure)).thenReturn(translated);
        var repository=new DeliveryCorrectionRepository(jdbc,reads,errors);
        var input=new Correction(1,RecordStatus.ANNULLED,null,null,"Conferência");
        assertThatThrownBy(()->repository.applyAndRead(UUID.randomUUID(),UUID.randomUUID(),input))
            .isSameAs(translated);
        verifyNoInteractions(reads);
    }
}
