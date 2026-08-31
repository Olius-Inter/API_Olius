package br.com.olius.olius_core_api.enums;

/**
 * Espelha o tipo ENUM nativo do PostgreSQL {@code approval_status_t}.
 * Utilizado em {@code pev.status}.
 */
public enum ApprovalStatus {
    PENDING,
    APPROVED,
    INACTIVE,
    REJECTED
}
