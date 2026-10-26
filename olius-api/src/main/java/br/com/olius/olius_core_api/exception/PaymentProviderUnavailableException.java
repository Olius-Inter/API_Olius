package br.com.olius.olius_core_api.exception;

import br.com.olius.olius_core_api.exception.enums.ApiErrorCode;

/** Não conserva corpo, credenciais nem mensagem do provedor. */
public final class PaymentProviderUnavailableException extends ApiException {
    public PaymentProviderUnavailableException() { super(ApiErrorCode.SERVICE_UNAVAILABLE); }
}
