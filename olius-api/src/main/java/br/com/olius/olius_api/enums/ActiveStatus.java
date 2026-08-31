package br.com.olius.olius_api.enums;

/**
 * Espelha o tipo ENUM nativo do PostgreSQL {@code active_status_t}.
 * Utilizado atualmente apenas em {@code driver.status}.
 */
public enum ActiveStatus {
    ACTIVE,
    INACTIVE
}
