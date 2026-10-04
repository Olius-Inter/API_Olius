package br.com.olius.olius_core_api.exception;

import br.com.olius.olius_core_api.exception.dto.FieldErrorResponse;
import br.com.olius.olius_core_api.exception.enums.ApiErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

@Component
public class ApiProblemFactory {
    private static final String TRACE_ATTRIBUTE = ApiProblemFactory.class.getName() + ".traceId";

    public String traceId(HttpServletRequest request) {
        Object existing = request.getAttribute(TRACE_ATTRIBUTE);
        if (existing instanceof String value) {
            return value;
        }
        String generated = UUID.randomUUID().toString();
        request.setAttribute(TRACE_ATTRIBUTE, generated);
        return generated;
    }

    public ProblemDetail create(ApiErrorCode code, HttpServletRequest request) {
        return create(code, status(code), request, List.of());
    }

    public ProblemDetail create(ApiErrorCode code, HttpStatusCode status,
                                HttpServletRequest request, List<FieldErrorResponse> errors) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail(code));
        HttpStatus standard = HttpStatus.resolve(status.value());
        problem.setTitle(standard == null ? "Erro HTTP" :
                code == ApiErrorCode.HTTP_REQUEST_ERROR ? "Erro na requisição" : title(code));
        problem.setType(URI.create("urn:olius:problem:" + code.name().toLowerCase(Locale.ROOT).replace('_', '-')));
        // A URL pode conter um token de QR. A ocorrência usa um identificador opaco.
        problem.setInstance(URI.create("urn:uuid:" + traceId(request)));
        problem.setProperty("code", code.name());
        problem.setProperty("timestamp", Instant.now().toString());
        problem.setProperty("traceId", traceId(request));
        if (!errors.isEmpty()) {
            problem.setProperty("errors", errors);
        }
        return problem;
    }

    public HttpStatus status(ApiErrorCode code) {
        return switch (code) {
            case VALIDATION_ERROR, MALFORMED_REQUEST, HTTP_REQUEST_ERROR -> HttpStatus.BAD_REQUEST;
            case AUTHENTICATION_REQUIRED -> HttpStatus.UNAUTHORIZED;
            case ACCESS_DENIED -> HttpStatus.FORBIDDEN;
            case RESOURCE_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case RESOURCE_CONFLICT, REGISTRATION_CONFLICT, DUPLICATE_TELEPHONE, PEV_ALREADY_EXISTS,
                 BUSINESS_STATE_CONFLICT, CONCURRENT_MODIFICATION -> HttpStatus.CONFLICT;
            case BUSINESS_RULE_VIOLATION -> HttpStatus.UNPROCESSABLE_CONTENT;
            case METHOD_NOT_ALLOWED -> HttpStatus.METHOD_NOT_ALLOWED;
            case UNSUPPORTED_MEDIA_TYPE -> HttpStatus.UNSUPPORTED_MEDIA_TYPE;
            case NOT_ACCEPTABLE -> HttpStatus.NOT_ACCEPTABLE;
            case INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
            case SERVICE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
        };
    }

    private String title(ApiErrorCode code) {
        return switch (status(code)) {
            case BAD_REQUEST -> "Requisição inválida";
            case UNAUTHORIZED -> "Autenticação necessária";
            case FORBIDDEN -> "Acesso negado";
            case NOT_FOUND -> "Recurso não encontrado";
            case CONFLICT -> "Conflito na operação";
            case UNPROCESSABLE_CONTENT -> "Dados inconsistentes";
            case METHOD_NOT_ALLOWED -> "Método não permitido";
            case UNSUPPORTED_MEDIA_TYPE -> "Formato não suportado";
            case NOT_ACCEPTABLE -> "Formato de resposta não disponível";
            case SERVICE_UNAVAILABLE -> "Serviço indisponível";
            default -> "Erro interno";
        };
    }

    private String detail(ApiErrorCode code) {
        return switch (code) {
            case VALIDATION_ERROR -> "Verifique os campos informados.";
            case MALFORMED_REQUEST -> "O corpo ou os parâmetros da requisição são inválidos.";
            case AUTHENTICATION_REQUIRED -> "É necessário apresentar uma autenticação válida.";
            case ACCESS_DENIED -> "Você não tem permissão para realizar esta operação.";
            case RESOURCE_NOT_FOUND -> "O recurso solicitado não foi encontrado.";
            case REGISTRATION_CONFLICT -> "Não foi possível concluir o cadastro com os dados informados.";
            case DUPLICATE_TELEPHONE -> "Este telefone já está associado ao cadastro.";
            case PEV_ALREADY_EXISTS -> "O responsável já possui um PEV cadastrado.";
            case RESOURCE_CONFLICT, BUSINESS_STATE_CONFLICT -> "A operação não é permitida no estado atual do recurso.";
            case BUSINESS_RULE_VIOLATION -> "Os dados informados são incompatíveis com a operação.";
            case CONCURRENT_MODIFICATION -> "A operação encontrou uma alteração concorrente. Consulte o estado atual antes de tentar novamente.";
            case METHOD_NOT_ALLOWED -> "O método HTTP não é permitido para este recurso.";
            case UNSUPPORTED_MEDIA_TYPE -> "O formato do conteúdo enviado não é suportado.";
            case NOT_ACCEPTABLE -> "Não há uma representação disponível no formato solicitado.";
            case HTTP_REQUEST_ERROR -> "Não foi possível atender à requisição.";
            case SERVICE_UNAVAILABLE -> "O serviço está temporariamente indisponível.";
            case INTERNAL_ERROR -> "Não foi possível concluir a operação. Informe o identificador do erro ao suporte.";
        };
    }
}
