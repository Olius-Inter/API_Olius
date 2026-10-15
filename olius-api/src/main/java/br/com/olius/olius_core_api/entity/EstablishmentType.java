package br.com.olius.olius_core_api.entity;
import jakarta.persistence.*;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
@Entity @Table(name="establishment_type") @Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
public class EstablishmentType {
    @Id private UUID id;
    @Column(length=100) private String name;
    @Column(length=255) private String description;
}
