package br.com.olius.olius_core_api.security;
import br.com.olius.olius_core_api.exception.ApiProblemFactory;
import br.com.olius.olius_core_api.exception.enums.ApiErrorCode;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;
public class AuthRateLimitFilter extends OncePerRequestFilter {
    private static final RequestMatcher LIMITED=new OrRequestMatcher(
        PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST,"/api/v1/auth/*/login"),
        PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST,"/api/v1/auth/*/refresh"),
        PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST,"/api/v1/auth/registrations/*"));
    private final AuthRateLimiter limiter;
    private final ApiProblemFactory factory;
    private final ObjectMapper mapper;
    public AuthRateLimitFilter(AuthRateLimiter limiter,ApiProblemFactory factory,ObjectMapper mapper) {
        this.limiter=limiter; this.factory=factory; this.mapper=mapper;
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
        throws IOException,ServletException {
        boolean limited=LIMITED.matches(request);
        // Não confiar em X-Forwarded-For fornecido diretamente pelo cliente.
        if(limited && !limiter.allow(request.getRemoteAddr())) {
            response.setStatus(429); response.setHeader("Retry-After","60");
            response.setHeader("X-Request-Id",factory.traceId(request));
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            mapper.writeValue(response.getOutputStream(),factory.create(ApiErrorCode.RATE_LIMITED,request)); return;
        }
        chain.doFilter(request,response);
    }
}
