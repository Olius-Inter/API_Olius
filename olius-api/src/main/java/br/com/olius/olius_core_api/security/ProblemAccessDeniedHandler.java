package br.com.olius.olius_core_api.security;

import br.com.olius.olius_core_api.exception.enums.ApiErrorCode;
import br.com.olius.olius_core_api.exception.ApiProblemFactory;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class ProblemAccessDeniedHandler implements AccessDeniedHandler {
    private final ApiProblemFactory factory;
    private final ObjectMapper mapper;

    public ProblemAccessDeniedHandler(ApiProblemFactory factory, ObjectMapper mapper) {
        this.factory = factory;
        this.mapper = mapper;
    }

    /**
     * Apresenta a rejeição de autorização como 403, sem decidir permissões.
     * Uma resposta já enviada é preservada.
     */
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException exception) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(403);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader("X-Request-Id", factory.traceId(request));
        mapper.writeValue(response.getOutputStream(), factory.create(ApiErrorCode.ACCESS_DENIED, request));
    }
}
