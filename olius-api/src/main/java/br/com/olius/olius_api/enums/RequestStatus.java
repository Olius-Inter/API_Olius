package br.com.olius.olius_api.enums;

/**
 * Espelha o tipo ENUM nativo do PostgreSQL {@code request_status_t}.
 * Utilizado em {@code collection_request.status}.
 */
public enum RequestStatus {
    PENDING,
    APPROVED,
    REJECTED,
    CANCELLED
}
