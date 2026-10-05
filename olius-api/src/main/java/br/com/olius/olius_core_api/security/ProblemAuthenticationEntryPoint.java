package br.com.olius.olius_core_api.security;

import br.com.olius.olius_core_api.exception.enums.ApiErrorCode;
import br.com.olius.olius_core_api.exception.ApiProblemFactory;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class ProblemAuthenticationEntryPoint implements AuthenticationEntryPoint {
    private final ApiProblemFactory factory;
    private final ObjectMapper mapper;

    public ProblemAuthenticationEntryPoint(ApiProblemFactory factory, ObjectMapper mapper) {
        this.factory = factory;
        this.mapper = mapper;
    }

    /**
     * Envia 401 com desafio Bearer sem reutilizar mensagens internas de autenticação.
     * Uma resposta já enviada é preservada; este método não valida tokens.
     */
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException exception) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(401);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        response.setHeader("X-Request-Id", factory.traceId(request));
        mapper.writeValue(response.getOutputStream(), factory.create(ApiErrorCode.AUTHENTICATION_REQUIRED, request));
    }
}
