package br.com.olius.olius_core_api.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** O ciclo de origem e o contratante são resolvidos no servidor. */
public record RenewalCheckoutRequest(@NotNull UUID planId) { }
