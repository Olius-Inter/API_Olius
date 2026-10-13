package br.com.olius.olius_core_api.security;
import br.com.olius.olius_core_api.exception.ApiProblemFactory;
import br.com.olius.olius_core_api.exception.PersistenceExceptionTranslator;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;
/** Falhas do banco durante autenticação ocorrem antes do ControllerAdvice. */
public class DatabaseSecurityFailureFilter extends OncePerRequestFilter {
    private final ApiProblemFactory factory; private final PersistenceExceptionTranslator translator;
    private final ObjectMapper mapper;
    public DatabaseSecurityFailureFilter(ApiProblemFactory factory,PersistenceExceptionTranslator translator,ObjectMapper mapper) {
        this.factory=factory; this.translator=translator; this.mapper=mapper;
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
        throws ServletException,IOException {
        try { chain.doFilter(request,response); }
        catch(DataAccessException failure) {
            if(response.isCommitted()) throw failure;
            var code=translator.translate(failure); response.setStatus(factory.status(code).value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.setHeader("X-Request-Id",factory.traceId(request));
            mapper.writeValue(response.getOutputStream(),factory.create(code,request));
        }
    }
}
