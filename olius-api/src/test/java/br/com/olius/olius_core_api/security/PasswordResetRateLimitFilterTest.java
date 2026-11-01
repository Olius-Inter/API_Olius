package br.com.olius.olius_core_api.security;

import br.com.olius.olius_core_api.exception.ApiProblemFactory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PasswordResetRateLimitFilterTest {
    @Test void encodedPathUsesSameQuotaAndForwardedHeaderCannotBypassIt() throws Exception {
        var clock=mock(Clock.class); when(clock.millis()).thenReturn(0L);
        var filter=new PasswordResetRateLimitFilter(new AuthRateLimiter(1,10,clock,Duration.ofMinutes(15)),new ApiProblemFactory(),new ObjectMapper());
        var first=new MockHttpServletRequest("POST","/api/v1/auth/password-reset-requests");
        filter.doFilter(first,new MockHttpServletResponse(),(req,res)-> { });
        var encoded=new MockHttpServletRequest("POST","/api/v1/auth/password-reset-%72equests"); encoded.addHeader("X-Forwarded-For","different-client");
        var response=new MockHttpServletResponse();
        filter.doFilter(encoded,response,(req,res)-> { throw new AssertionError("Quota must apply"); });
        assertThat(response.getStatus()).isEqualTo(429); assertThat(response.getHeader("Retry-After")).isEqualTo("900");
        assertThat(response.getContentAsString()).contains("RATE_LIMITED");
        filter.doFilter(new MockHttpServletRequest("POST","/api/v1/auth/password-resets"),new MockHttpServletResponse(),(req,res)-> { });
        when(clock.millis()).thenReturn(Duration.ofMinutes(15).toMillis());
        filter.doFilter(first,new MockHttpServletResponse(),(req,res)-> { });
    }
    @Test void loginAndReadDoNotConsumeResetQuota() throws Exception {
        var filter=new PasswordResetRateLimitFilter(new AuthRateLimiter(1,10,Clock.fixed(Instant.EPOCH,ZoneOffset.UTC),Duration.ofMinutes(15)),new ApiProblemFactory(),new ObjectMapper());
        for(String path:new String[]{"/api/v1/auth/mobile/login","/api/v1/auth/web/csrf","/api/v1/auth/password-reset-requests"})
            filter.doFilter(new MockHttpServletRequest("POST",path),new MockHttpServletResponse(),(req,res)-> { });
    }
}
