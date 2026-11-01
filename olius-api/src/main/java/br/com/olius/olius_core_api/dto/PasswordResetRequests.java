package br.com.olius.olius_core_api.dto;

import br.com.olius.olius_core_api.validation.StrongPassword;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.media.Schema;

public final class PasswordResetRequests {
    private PasswordResetRequests() { }
    public record Request(@NotBlank @Email @Size(max=255) String email) {
        public Request { if(email!=null) email=email.strip(); }
        @Override public String toString() { return "PasswordResetRequest[redacted]"; }
    }
    public record Complete(@Schema(accessMode=Schema.AccessMode.WRITE_ONLY) @NotBlank @Size(max=128) String token,
                           @StrongPassword String password) {
        @Override public String toString() { return "PasswordResetComplete[redacted]"; }
    }
}
