package br.com.olius.olius_core_api.exception;

import br.com.olius.olius_core_api.exception.enums.ApiErrorCode;

public final class PasswordResetInvalidException extends ApiException {
    public PasswordResetInvalidException() { super(ApiErrorCode.PASSWORD_RESET_INVALID); }
}
