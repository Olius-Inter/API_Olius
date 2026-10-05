package br.com.olius.olius_core_api.exception;

import br.com.olius.olius_core_api.exception.dto.FieldErrorResponse;
import br.com.olius.olius_core_api.exception.enums.ApiErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Fronteira HTTP do tratamento MVC: preserva status/cabeçalhos e padroniza o corpo.
 * Falhas em filtros anteriores ao MVC exigem os componentes próprios de segurança.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private final ApiProblemFactory factory;
    private final PersistenceExceptionTranslator translator;

    public GlobalExceptionHandler(ApiProblemFactory factory, PersistenceExceptionTranslator translator) {
        this.factory = factory;
        this.translator = translator;
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Object> handleApplication(ApiException ex, HttpServletRequest request) {
        return response(ex.getCode(), request);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Object> handleAuthentication(AuthenticationException ex, HttpServletRequest request) {
        var headers = new HttpHeaders();
        headers.set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        return response(ApiErrorCode.AUTHENTICATION_REQUIRED, request, headers);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Object> handleAccess(AccessDeniedException ex, HttpServletRequest request) {
        return response(ApiErrorCode.ACCESS_DENIED, request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception ex, HttpServletRequest request) {
        // Preserva status/cabeçalhos de exceções HTTP não cobertas explicitamente pelo MVC.
        if (ex instanceof ErrorResponse error) {
            return httpResponse(ex, error.getStatusCode(), error.getHeaders(), request, List.of());
        }
        ApiErrorCode code = translator.translate(ex);
        if (code == ApiErrorCode.INTERNAL_ERROR || code == ApiErrorCode.SERVICE_UNAVAILABLE) {
            logFailure(ex, code, factory.status(code), request);
        }
        return response(code, request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (request instanceof ServletWebRequest servlet) {
            if (servlet.getResponse() != null && servlet.getResponse().isCommitted()) {
                return null;
            }
            List<FieldErrorResponse> errors = List.of();
            if (ex instanceof MethodArgumentNotValidException validation) {
                errors = validation.getBindingResult().getAllErrors().stream()
                    .map(error -> new FieldErrorResponse(
                        error instanceof org.springframework.validation.FieldError field ? field.getField() : "_request",
                        "INVALID_VALUE", "Valor inválido para este campo."))
                    .distinct()
                    .sorted(Comparator.comparing(FieldErrorResponse::field))
                    .toList();
            } else if (ex instanceof HandlerMethodValidationException validation && !validation.isForReturnValue()) {
                // Nomes/valores fornecidos pelo cliente não são interpolados no contrato.
                errors = List.of(new FieldErrorResponse("_request", "INVALID_VALUE", "Parâmetro inválido."));
            }
            return httpResponse(ex, status, headers, servlet.getRequest(), errors);
        }
        return super.handleExceptionInternal(ex, body, headers, status, request);
    }

    private ResponseEntity<Object> httpResponse(Exception ex, HttpStatusCode status,
            HttpHeaders originalHeaders, HttpServletRequest request, List<FieldErrorResponse> errors) {
        ApiErrorCode code = switch (status.value()) {
            case 400 -> ex instanceof MethodArgumentNotValidException || ex instanceof HandlerMethodValidationException
                    ? ApiErrorCode.VALIDATION_ERROR : ApiErrorCode.MALFORMED_REQUEST;
            case 401 -> ApiErrorCode.AUTHENTICATION_REQUIRED;
            case 403 -> ApiErrorCode.ACCESS_DENIED;
            case 404 -> ApiErrorCode.RESOURCE_NOT_FOUND;
            case 405 -> ApiErrorCode.METHOD_NOT_ALLOWED;
            case 406 -> ApiErrorCode.NOT_ACCEPTABLE;
            case 409 -> ApiErrorCode.RESOURCE_CONFLICT;
            case 415 -> ApiErrorCode.UNSUPPORTED_MEDIA_TYPE;
            case 422 -> ApiErrorCode.BUSINESS_RULE_VIOLATION;
            case 503 -> ApiErrorCode.SERVICE_UNAVAILABLE;
            default -> status.is5xxServerError() ? ApiErrorCode.INTERNAL_ERROR : ApiErrorCode.HTTP_REQUEST_ERROR;
        };
        if (status.is5xxServerError()) {
            logFailure(ex, code, status, request);
        }
        HttpHeaders headers = new HttpHeaders();
        headers.putAll(originalHeaders);
        headers.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        headers.set("X-Request-Id", factory.traceId(request));
        return new ResponseEntity<>(factory.create(code, status, request, errors), headers, status);
    }

    /** Registra apenas metadados; nunca mensagens, SQL ou stack traces do driver. */
    private void logFailure(Exception ex, ApiErrorCode code, HttpStatusCode status,
                            HttpServletRequest request) {
        if (LOG.isErrorEnabled()) {
            var diagnostic = SafeExceptionDiagnostic.from(ex);
            LOG.error("Falha na requisição traceId={} code={} status={} exceptionType={} rootCauseType={} sqlState={}",
                    factory.traceId(request), code, status.value(), ex.getClass().getName(),
                    diagnostic.rootCauseType(), diagnostic.sqlState());
        }
    }

    private ResponseEntity<Object> response(ApiErrorCode code, HttpServletRequest request) {
        return response(code, request, new HttpHeaders());
    }

    private ResponseEntity<Object> response(ApiErrorCode code, HttpServletRequest request, HttpHeaders headers) {
        headers.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        headers.set("X-Request-Id", factory.traceId(request));
        return new ResponseEntity<>(factory.create(code, request), headers, factory.status(code));
    }
}
