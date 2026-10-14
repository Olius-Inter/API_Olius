package br.com.olius.olius_core_api.entity;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name="addresses")
@Getter
@NoArgsConstructor(access=AccessLevel.PROTECTED)
public class Address {
    public enum OwnerKind { ESTABLISHMENT, PEV }
    @Id private UUID id;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name="owner_kind", columnDefinition="address_owner_t", updatable=false) private OwnerKind ownerKind;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(length=2, columnDefinition="char(2)") private String state;
    @Column(length=100) private String city;
    @Column(length=100) private String neighborhood;
    @Column(length=150) private String street;
    @Column(length=20) private String number;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(length=8, columnDefinition="char(8)") private String cep;
    @Column(length=150) private String complement;
    @Column(precision=9, scale=6) private BigDecimal latitude;
    @Column(precision=9, scale=6) private BigDecimal longitude;
    public record PostalDetails(String state, String city, String neighborhood, String street,
                                String number, String cep, String complement) { }
    public record Coordinates(BigDecimal latitude, BigDecimal longitude) { }

    public Address(UUID id, PostalDetails postal, Coordinates coordinates) {
        this.id=id;
        ownerKind=OwnerKind.ESTABLISHMENT;
        state=postal.state(); city=postal.city(); neighborhood=postal.neighborhood();
        street=postal.street(); number=postal.number(); cep=postal.cep(); complement=postal.complement();
        latitude=coordinates.latitude(); longitude=coordinates.longitude();
    }
}
