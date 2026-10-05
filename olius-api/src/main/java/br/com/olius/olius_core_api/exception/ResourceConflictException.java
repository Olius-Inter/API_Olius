package br.com.olius.olius_core_api.exception;

import br.com.olius.olius_core_api.exception.enums.ApiErrorCode;

public final class ResourceConflictException extends ApiException {
    public ResourceConflictException() {
        super(ApiErrorCode.RESOURCE_CONFLICT);
    }
}
