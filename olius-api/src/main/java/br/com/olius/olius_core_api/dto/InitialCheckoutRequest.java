package br.com.olius.olius_core_api.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** O cliente escolhe a oferta, nunca o preço ou a identidade do contratante. */
public record InitialCheckoutRequest(@NotNull UUID planId) { }
