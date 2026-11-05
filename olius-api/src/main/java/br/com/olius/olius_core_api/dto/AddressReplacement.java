package br.com.olius.olius_core_api.dto;

import br.com.olius.olius_core_api.dto.IdentityRequests.AddressInput;
import br.com.olius.olius_core_api.validation.PairedCoordinates;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/** Substitui o endereço inteiro; opcionais ausentes são limpos, nunca herdados do endereço antigo. */
public record AddressReplacement(@NotNull @Valid @PairedCoordinates AddressInput address) { }
