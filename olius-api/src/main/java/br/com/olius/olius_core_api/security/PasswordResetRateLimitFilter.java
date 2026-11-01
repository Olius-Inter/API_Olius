package br.com.olius.olius_core_api.security;

import br.com.olius.olius_core_api.exception.ApiProblemFactory;
import br.com.olius.olius_core_api.exception.enums.ApiErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/** Cotas independentes por operação/IP observado; não confia em X-Forwarded-For. */
public class PasswordResetRateLimitFilter extends OncePerRequestFilter {
    private static final RequestMatcher REQUEST=PathPatternRequestMatcher.withDefaults()
        .matcher(HttpMethod.POST,"/api/v1/auth/password-reset-requests");
    private static final RequestMatcher COMPLETE=PathPatternRequestMatcher.withDefaults()
        .matcher(HttpMethod.POST,"/api/v1/auth/password-resets");
    private final AuthRateLimiter limiter;
    private final ApiProblemFactory factory;
    private final ObjectMapper mapper;
    public PasswordResetRateLimitFilter(AuthRateLimiter limiter,ApiProblemFactory factory,ObjectMapper mapper) {
        this.limiter=limiter; this.factory=factory; this.mapper=mapper;
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws IOException,ServletException {
        boolean issuing=REQUEST.matches(request);
        if((issuing || COMPLETE.matches(request)) && !limiter.allow((issuing?"issue:":"complete:")+request.getRemoteAddr())) {
            response.setStatus(429); response.setHeader("Retry-After","900");
            response.setHeader("X-Request-Id",factory.traceId(request));
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            mapper.writeValue(response.getOutputStream(),factory.create(ApiErrorCode.RATE_LIMITED,request));
            return;
        }
        chain.doFilter(request,response);
    }
}
