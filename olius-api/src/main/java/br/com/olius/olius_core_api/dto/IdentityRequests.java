package br.com.olius.olius_core_api.dto;
import br.com.olius.olius_core_api.entity.enums.UserStatus;
import br.com.olius.olius_core_api.validation.StrongPassword;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public final class IdentityRequests {
    private IdentityRequests() { }
    public record CitizenRegistration(@NotBlank @Size(max=150) String name,
        @NotBlank @Email @Size(max=255) String email, @StrongPassword String password,
        @NotBlank @Pattern(regexp="\\d{11}") String cpf,
        List<@NotNull @Size(min=10,max=15) String> telephones) {
        @Override public String toString() { return "CitizenRegistration[redacted]"; }
    }
    public record EstablishmentRegistration(@NotBlank @Size(max=150) String name,
        @NotBlank @Email @Size(max=255) String email, @StrongPassword String password,
        @NotBlank @Pattern(regexp="\\d{14}") String cnpj, @NotNull UUID typeId,
        String description, @NotNull @Valid AddressInput address,
        List<@NotNull @Size(min=10,max=15) String> telephones) {
        @Override public String toString() { return "EstablishmentRegistration[redacted]"; }
    }
    public record AddressInput(@NotBlank @Pattern(regexp="[A-Z]{2}") String state,
        @NotBlank @Size(max=100) String city, @NotBlank @Size(max=100) String neighborhood,
        @NotBlank @Size(max=150) String street, @NotBlank @Size(max=20) String number,
        @NotBlank @Pattern(regexp="\\d{8}") String cep, @Size(max=150) String complement,
        @DecimalMin("-90") @DecimalMax("90") BigDecimal latitude,
        @DecimalMin("-180") @DecimalMax("180") BigDecimal longitude) { }
    public record Login(@NotBlank @Email @Size(max=255) String email,
                        @NotNull @Size(min=1,max=256) String password) {
        @Override public String toString() { return "Login[redacted]"; }
    }
    public record Refresh(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{43}") String refreshToken) {
        @Override public String toString() { return "Refresh[redacted]"; }
    }
    public record AdminRegistration(@NotBlank @Size(max=150) String name,
        @NotBlank @Email @Size(max=255) String email, @StrongPassword String password) {
        @Override public String toString() { return "AdminRegistration[redacted]"; }
    }
    public record StatusChange(@NotNull UserStatus status) { }
}
