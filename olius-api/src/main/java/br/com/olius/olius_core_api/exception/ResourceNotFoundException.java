package br.com.olius.olius_core_api.exception;

import br.com.olius.olius_core_api.exception.enums.ApiErrorCode;

public final class ResourceNotFoundException extends ApiException {
    public ResourceNotFoundException() {
        super(ApiErrorCode.RESOURCE_NOT_FOUND);
    }
}
