package br.com.olius.olius_core_api.dto;
import br.com.olius.olius_core_api.entity.UserStatus;
import br.com.olius.olius_core_api.entity.UserType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
public final class IdentityResponses {
    private IdentityResponses() { }
    public record Registration(UUID id, UserType userType) { }
    public record AddressView(UUID id, String state, String city, String neighborhood, String street,
        String number, String cep, String complement, BigDecimal latitude, BigDecimal longitude) { }
    public record Profile(UUID id, String name, String email, UserType userType, UserStatus status,
        Instant createdAt, List<String> telephones, String cpf, String cnpj, String qrToken,
        UUID establishmentTypeId, String description, AddressView address) { }
    public record TypeView(UUID id, String name, String description) { }
    public record Access(String accessToken, String tokenType, long expiresIn, Instant expiresAt) {
        @Override public String toString() { return "Access[redacted]"; }
    }
    public record MobileTokens(String accessToken, String tokenType, long expiresIn, Instant expiresAt,
        String refreshToken, Instant refreshExpiresAt) {
        @Override public String toString() { return "MobileTokens[redacted]"; }
    }
    public record SessionIdentity(UUID userId, UUID sessionId, UserType userType, Instant sessionExpiresAt) { }
    public record Csrf(String headerName, String token) {
        @Override public String toString() { return "Csrf[redacted]"; }
    }
}
