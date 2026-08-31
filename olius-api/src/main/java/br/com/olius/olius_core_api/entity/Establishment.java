package br.com.olius.olius_core_api.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

/**
 * Tabela: establishment
 *
 * Mesma estratégia de PK compartilhada de {@link Citizen} (@OneToOne +
 * @MapsId em relação a User).
 *
 * address_id é NOT NULL + UNIQUE ⇒ 1:1 obrigatório e exclusivo, mapeado
 * como @OneToOne owning side. Como o Establishment é sempre criado já
 * com um endereço (não faz sentido existir um sem o outro), aplicamos
 * CascadeType.PERSIST/MERGE aqui — mas não CascadeType.REMOVE, pois a FK
 * no banco é ON DELETE RESTRICT (a remoção deve ser uma decisão explícita
 * da camada de serviço, não um efeito colateral automático do ORM).
 *
 * "points" segue a mesma lógica de desnormalização documentada em
 * Citizen — histórico granular em collection.points_earned.
 */
@Entity
@Table(name = "establishment")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(onlyExplicitlyIncluded = true)
public class Establishment {

    @Id
    @Column(name = "id")
    @EqualsAndHashCode.Include
    @ToString.Include
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId
    @JoinColumn(name = "id")
    private User user;

    @Column(name = "cnpj", nullable = false, unique = true, length = 14, columnDefinition = "CHAR(14)")
    @ToString.Include
    private String cnpj;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Builder.Default
    @Column(name = "is_pev", nullable = false)
    private boolean isPev = false;

    @Column(name = "qr_token", nullable = false, unique = true, length = 64)
    private String qrToken;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "type_id", nullable = false)
    private EstablishmentType type;

    @OneToOne(fetch = FetchType.LAZY, optional = false, cascade = {CascadeType.PERSIST, CascadeType.MERGE})
    @JoinColumn(name = "address_id", nullable = false, unique = true)
    private Address address;

    @Builder.Default
    @Column(name = "points", nullable = false)
    @ToString.Include
    private Integer points = 0;
}
