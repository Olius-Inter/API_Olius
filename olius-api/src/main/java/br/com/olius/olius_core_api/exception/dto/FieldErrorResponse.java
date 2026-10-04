package br.com.olius.olius_core_api.exception.dto;

/** Não inclui o valor rejeitado, que pode conter senha, CPF ou token. */
public record FieldErrorResponse(String field, String code, String message) {
}
