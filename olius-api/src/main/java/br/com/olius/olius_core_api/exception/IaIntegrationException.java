package br.com.olius.olius_core_api.exception;

import br.com.olius.olius_core_api.exception.enums.ApiErrorCode;

/** Mensagem pública estável; causas são preservadas somente para diagnóstico de transporte. */
public final class IaIntegrationException extends ApiException {
    public IaIntegrationException(ApiErrorCode code) {
        super(code);
    }

    public IaIntegrationException(ApiErrorCode code, Throwable cause) {
        super(code, cause);
    }
}
