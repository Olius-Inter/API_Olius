package br.com.olius.olius_api.enums;

/**
 * Espelha o tipo ENUM nativo do PostgreSQL {@code user_type_t}.
 * Define a especialização de cada linha da tabela {@code users}:
 * apenas ESTABLISHMENT e CITIZENS possuem tabela de extensão própria
 * (establishment / citizens); ADMIN não possui tabela de extensão.
 */
public enum UserType {
    ESTABLISHMENT,
    CITIZENS,
    ADMIN
}
