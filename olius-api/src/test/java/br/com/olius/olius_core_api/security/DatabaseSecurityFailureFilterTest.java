package br.com.olius.olius_core_api.security;
import br.com.olius.olius_core_api.exception.*;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.mock.web.*;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
class DatabaseSecurityFailureFilterTest {
    @Test void databaseOutageBeforeMvcReturnsSafe503() throws Exception {
        var mapper=new ObjectMapper();
        var filter=new DatabaseSecurityFailureFilter(new ApiProblemFactory(),new PersistenceExceptionTranslator(),mapper);
        var request=new MockHttpServletRequest(); var response=new MockHttpServletResponse();
        filter.doFilter(request,response,(req,res) -> {
            throw new CannotGetJdbcConnectionException("secret driver message",new SQLException("personal data","08006"));
        });
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("SERVICE_UNAVAILABLE").doesNotContain("personal data","secret driver message");
    }
}
