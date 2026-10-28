package br.com.olius.olius_core_api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuração do canal; não contém o segredo do convite nem a senha SMTP. */
@ConfigurationProperties("olius.admin-invitations.email")
public record AdminInvitationEmailProperties(boolean enabled, String from, String acceptanceUrl) { }
