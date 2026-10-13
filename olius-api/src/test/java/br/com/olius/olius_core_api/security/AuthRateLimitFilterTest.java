package br.com.olius.olius_core_api.security;
import br.com.olius.olius_core_api.exception.ApiProblemFactory;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
class AuthRateLimitFilterTest {
    @Test void repeatedLoginReturns429WithRetryHeaderAndProblemJson() throws Exception {
        var filter=new AuthRateLimitFilter(new AuthRateLimiter(1,10,Clock.systemUTC()),new ApiProblemFactory(),new ObjectMapper());
        filter.doFilter(new MockHttpServletRequest("POST","/api/v1/auth/mobile/login"),new MockHttpServletResponse(),(req,res) -> { });
        var response=new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("POST","/api/v1/auth/mobile/%6Cogin"),response,(req,res) -> {
            throw new AssertionError("Limite deve impedir continuar a cadeia");
        });
        assertThat(response.getStatus()).isEqualTo(429); assertThat(response.getHeader("Retry-After")).isEqualTo("60");
        assertThat(response.getContentAsString()).contains("RATE_LIMITED");
    }
}
