package br.com.olius.olius_core_api.security;
import br.com.olius.olius_core_api.exception.ApiProblemFactory;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
class AuthRateLimitFilterTest {
    @Test void registrationSharesTheAuthenticationQuota() throws Exception {
        var filter=new AuthRateLimitFilter(new AuthRateLimiter(1,10,Clock.systemUTC()),new ApiProblemFactory(),new ObjectMapper());
        var first=new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("POST","/api/v1/auth/registrations/citizens"),first,(req,res) -> res.getWriter().write("allowed"));
        assertThat(first.getContentAsString()).isEqualTo("allowed");
        var second=new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("POST","/api/v1/auth/registrations/establishments"),second,(req,res) -> { throw new AssertionError("Quota exceeded"); });
        assertThat(second.getStatus()).isEqualTo(429);
    }
    @Test void invitationAcceptanceIsRateLimitedWithoutAuthentication() throws Exception {
        var filter=new AuthRateLimitFilter(new AuthRateLimiter(1,10,Clock.systemUTC()),new ApiProblemFactory(),new ObjectMapper());
        filter.doFilter(new MockHttpServletRequest("POST","/api/v1/auth/admin-invitations/acceptance"),new MockHttpServletResponse(),(req,res) -> { });
        var response=new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("POST","/api/v1/auth/admin-invitations/acceptance"),response,(req,res) -> {
            throw new AssertionError("Limite deve proteger o aceite");
        });
        assertThat(response.getStatus()).isEqualTo(429);
    }

    @Test void fetchingCsrfDoesNotConsumeAuthenticationQuota() throws Exception {
        var filter=new AuthRateLimitFilter(new AuthRateLimiter(1,10,Clock.systemUTC()),new ApiProblemFactory(),new ObjectMapper());
        for(int i=0;i<3;i++) {
            var response=new MockHttpServletResponse();
            filter.doFilter(new MockHttpServletRequest("GET","/api/v1/auth/web/csrf"),response,(req,res) -> res.getWriter().write("allowed"));
            assertThat(response.getContentAsString()).isEqualTo("allowed");
        }
        var login=new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("POST","/api/v1/auth/mobile/login"),login,(req,res) -> res.getWriter().write("allowed"));
        assertThat(login.getContentAsString()).isEqualTo("allowed");
    }

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
