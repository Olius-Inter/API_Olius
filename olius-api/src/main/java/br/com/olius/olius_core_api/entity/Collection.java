package br.com.olius.olius_core_api.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Tabela: collection
 *
 * collection_request_id é NULLable + UNIQUE ⇒ 1:1 OPCIONAL com
 * CollectionRequest (uma coleta pode não estar vinculada a nenhuma
 * solicitação prévia — ex.: rota de esvaziamento de PEV). Mapeada como
 * owning side aqui, unidirecional (CollectionRequest não referencia de
 * volta; se precisarem navegar de CollectionRequest -> Collection no
 * futuro, basta adicionar @OneToOne(mappedBy = "collectionRequest") lá).
 *
 * points_earned só existe quando collection_request_id não é nulo
 * (ck_collection_points_earned_establishment) — regra de negócio que
 * deve ser reforçada na camada de serviço antes do persist.
 */
@Entity
@Table(name = "collection")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(onlyExplicitlyIncluded = true)
public class Collection {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    @EqualsAndHashCode.Include
    @ToString.Include
    private UUID id;

    @Column(name = "collected_volume_liters", nullable = false, precision = 8, scale = 2)
    @ToString.Include
    private BigDecimal collectedVolumeLiters;

    @Column(name = "points_earned")
    private Integer pointsEarned;

    @Column(name = "observation", columnDefinition = "TEXT")
    private String observation;

    @Column(name = "collection_date", nullable = false)
    private LocalDateTime collectionDate;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @OneToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "collection_request_id", unique = true)
    private CollectionRequest collectionRequest;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "driver_id", nullable = false)
    private Driver driver;
}
