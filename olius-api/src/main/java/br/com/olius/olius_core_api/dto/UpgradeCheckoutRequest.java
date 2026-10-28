package br.com.olius.olius_core_api.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record UpgradeCheckoutRequest(@NotNull UUID planId) { }
