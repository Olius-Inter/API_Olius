package br.com.olius.olius_core_api.dto;

import br.com.olius.olius_core_api.validation.StrongPassword;
import jakarta.validation.constraints.*;

public final class AdminInvitationRequests {
    private AdminInvitationRequests() { }

    public record Issue(@NotBlank @Size(max=150) String name,
                        @NotBlank @Email @Size(max=255) String email) {
        @Override public String toString() { return "Issue[redacted]"; }
    }

    public record Accept(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{43}") String token,
                         @StrongPassword String password) {
        @Override public String toString() { return "Accept[redacted]"; }
    }
}
