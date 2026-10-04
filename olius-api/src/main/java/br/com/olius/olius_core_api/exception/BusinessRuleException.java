package br.com.olius.olius_core_api.exception;

import br.com.olius.olius_core_api.exception.enums.ApiErrorCode;

/** Rejeição esperada. Novos motivos específicos entram no catálogo na sprint do domínio. */
public final class BusinessRuleException extends ApiException {
    public enum Reason {
        STATE_CONFLICT, INCONSISTENT_DATA
    }

    public BusinessRuleException(Reason reason) {
        super(switch (reason) {
            case STATE_CONFLICT -> ApiErrorCode.BUSINESS_STATE_CONFLICT;
            case INCONSISTENT_DATA -> ApiErrorCode.BUSINESS_RULE_VIOLATION;
        });
    }
}
