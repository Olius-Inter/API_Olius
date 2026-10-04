package br.com.olius.olius_core_api.exception;

import br.com.olius.olius_core_api.exception.enums.ApiErrorCode;

import java.util.Objects;

/** A mensagem pública é definida pelo catálogo, nunca pelo texto de uma causa SQL. */
public abstract class ApiException extends RuntimeException {
    private final ApiErrorCode code;

    protected ApiException(ApiErrorCode code) {
        super(Objects.requireNonNull(code).name());
        this.code = code;
    }

    public final ApiErrorCode getCode() {
        return code;
    }
}
