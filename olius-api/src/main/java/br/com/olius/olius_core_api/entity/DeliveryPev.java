package br.com.olius.olius_core_api.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Tabela: delivery_pev
 *
 * N:1 tanto com Citizen quanto com Pev (um cidadão faz várias entregas;
 * um PEV recebe várias entregas). Unidirecional nos dois casos — é o
 * registro histórico/transacional, consultado tipicamente via
 * repositório paginado, não como coleção carregada em memória a partir
 * de Citizen ou Pev.
 */
@Entity
@Table(name = "delivery_pev")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(onlyExplicitlyIncluded = true)
public class DeliveryPev {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    @EqualsAndHashCode.Include
    @ToString.Include
    private UUID id;

    @Column(name = "oil_volume_liters", nullable = false, precision = 8, scale = 2)
    @ToString.Include
    private BigDecimal oilVolumeLiters;

    @Column(name = "points_earned", nullable = false)
    @ToString.Include
    private Integer pointsEarned;

    @Column(name = "delivery_date", nullable = false)
    private LocalDateTime deliveryDate;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "citizen_id", nullable = false)
    private Citizen citizen;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pev_id", nullable = false)
    private Pev pev;
}
