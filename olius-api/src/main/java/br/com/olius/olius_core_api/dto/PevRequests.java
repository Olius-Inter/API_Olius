package br.com.olius.olius_core_api.dto;

import br.com.olius.olius_core_api.dto.IdentityRequests.AddressInput;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public final class PevRequests {
    private PevRequests() { }

    public record Create(@NotNull @Valid AddressInput address) { }
}
