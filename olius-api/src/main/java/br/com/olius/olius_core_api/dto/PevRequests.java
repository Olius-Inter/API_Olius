package br.com.olius.olius_core_api.dto;

import br.com.olius.olius_core_api.dto.IdentityRequests.AddressInput;
import br.com.olius.olius_core_api.entity.enums.PevStatus;
import br.com.olius.olius_core_api.validation.PairedCoordinates;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public final class PevRequests {
    private PevRequests() { }

    public record Create(@NotNull @Valid AddressInput address) { }
    public record UpdateAddress(@NotNull @Valid @PairedCoordinates AddressInput address) { }
    public record StatusChange(@NotNull PevStatus status) { }
}
