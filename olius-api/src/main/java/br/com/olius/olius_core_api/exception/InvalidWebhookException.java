package br.com.olius.olius_core_api.exception;

import br.com.olius.olius_core_api.exception.enums.ApiErrorCode;

/** Não inclui assinatura, corpo ou segredo na mensagem pública. */
public final class InvalidWebhookException extends ApiException {
    public InvalidWebhookException() { super(ApiErrorCode.MALFORMED_REQUEST); }
}
