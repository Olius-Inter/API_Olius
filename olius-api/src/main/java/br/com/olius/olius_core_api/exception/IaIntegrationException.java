package br.com.olius.olius_core_api.exception;

import br.com.olius.olius_core_api.exception.enums.ApiErrorCode;

/** Nunca transporta corpo, URL ou mensagem recebida do provedor. */
public final class IaIntegrationException extends ApiException {
    public IaIntegrationException(ApiErrorCode code) {
        super(code);
    }
}
