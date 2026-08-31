package br.com.olius.olius_core_api.entity;

import br.com.olius.olius_core_api.enums.RequestStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Tabela: collection_request
 *
 * N:1 com Establishment (um estabelecimento pode ter várias solicitações
 * ao longo do tempo). Unidirecional: Establishment não mantém
 * List<CollectionRequest> — se a listagem por estabelecimento for um
 * caso de uso frequente, prefira um método de repositório
 * (findByEstablishmentId) a uma coleção sempre carregável na entidade.
 *
 * request_at faz o papel de "created_at" desta tabela (única coluna com
 * DEFAULT CURRENT_TIMESTAMP na criação) — mapeado com @CreationTimestamp.
 */
@Entity
@Table(name = "collection_request")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(onlyExplicitlyIncluded = true)
public class CollectionRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    @EqualsAndHashCode.Include
    @ToString.Include
    private UUID id;

    @Column(name = "estimated_volume_liters", nullable = false, precision = 8, scale = 2)
    @ToString.Include
    private BigDecimal estimatedVolumeLiters;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "status", nullable = false, columnDefinition = "request_status_t")
    @ToString.Include
    private RequestStatus status;

    @Column(name = "observation", columnDefinition = "TEXT")
    private String observation;

    @CreationTimestamp
    @Column(name = "request_at", nullable = false, updatable = false)
    private LocalDateTime requestAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "establishment_id", nullable = false)
    private Establishment establishment;

    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "approved_by")
    private User approvedBy;
}
