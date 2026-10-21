package br.com.olius.olius_core_api.exception;

import br.com.olius.olius_core_api.exception.enums.ApiErrorCode;

public final class AdminInvitationDeliveryException extends ApiException {
    public AdminInvitationDeliveryException(Throwable cause) {
        super(ApiErrorCode.ADMIN_INVITATION_DELIVERY_FAILED);
        initCause(cause);
    }
}
