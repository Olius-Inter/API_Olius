package br.com.olius.olius_api.enums;

/**
 * Espelha o tipo ENUM nativo do PostgreSQL {@code operation_status_t}.
 * Utilizado apenas nas tabelas de auditoria (*_log.operation) para
 * indicar qual operação de escrita originou o registro de auditoria.
 */
public enum OperationStatus {
    INSERT,
    UPDATE,
    DELETE
}
