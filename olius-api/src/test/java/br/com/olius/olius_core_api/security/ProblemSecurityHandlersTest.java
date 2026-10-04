package br.com.olius.olius_core_api.security;

import br.com.olius.olius_core_api.exception.ApiProblemFactory;
import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.ProblemDetailJacksonMixin;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.access.AccessDeniedException;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

class ProblemSecurityHandlersTest {
    private final ApiProblemFactory factory = new ApiProblemFactory();
    private final JsonMapper mapper = JsonMapper.builder()
            .addMixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class).build();

    @Test
    void unauthorizedHasBearerHeaderAndSafeContract() throws Exception {
        var request = new MockHttpServletRequest("GET", "/qr/secret");
        var response = new MockHttpServletResponse();
        new ProblemAuthenticationEntryPoint(factory, mapper)
            .commence(request, response, new BadCredentialsException("secret password"));
        var body = mapper.readTree(response.getContentAsString());
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("Bearer");
        assertThat(body.get("code").asText()).isEqualTo("AUTHENTICATION_REQUIRED");
        assertThat(body.get("traceId").asText()).isEqualTo(response.getHeader("X-Request-Id"));
        assertThat(response.getContentAsString()).doesNotContain("secret", "password", "properties");
    }

    @Test
    void forbiddenUsesSameContract() throws Exception {
        var response = new MockHttpServletResponse();
        new ProblemAccessDeniedHandler(factory, mapper).handle(new MockHttpServletRequest(), response,
                new AccessDeniedException("private"));
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(mapper.readTree(response.getContentAsString()).get("code").asText()).isEqualTo("ACCESS_DENIED");
        assertThat(response.getContentAsString()).doesNotContain("private");
    }

    @Test
    void doesNotRewriteCommittedResponse() throws Exception {
        var response = new MockHttpServletResponse();
        response.setStatus(202);
        response.flushBuffer();
        new ProblemAuthenticationEntryPoint(factory, mapper).commence(new MockHttpServletRequest(),
                response, new BadCredentialsException("private"));
        assertThat(response.getStatus()).isEqualTo(202);
    }
}
