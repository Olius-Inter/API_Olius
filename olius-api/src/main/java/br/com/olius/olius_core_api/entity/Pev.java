package br.com.olius.olius_core_api.entity;

import br.com.olius.olius_core_api.enums.ApprovalStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Tabela: pev (Ponto de Entrega Voluntária)
 *
 * citizen_id e establishment_id são ambos NULLable + UNIQUE, e a
 * CHECK ck_pev_single_owner garante no banco que exatamente um dos dois
 * esteja preenchido (XOR). Isso NÃO é expressável de forma nativa em
 * anotações JPA — recomenda-se validação equivalente na camada de
 * serviço/bean validation (ex.: @AssertTrue customizado) antes do
 * persist, para falhar cedo com uma mensagem amigável em vez de deixar
 * a constraint do banco estourar uma exceção genérica.
 * O mesmo vale para ck_pev_approved_at (status=APPROVED ⇔ approved_at
 * preenchido) e ck_pev_approval_date (approved_at >= created_at).
 *
 * address_id é NOT NULL + UNIQUE ⇒ 1:1 obrigatório, mesma lógica de
 * cascade PERSIST/MERGE aplicada em Establishment.address.
 */
@Entity
@Table(name = "pev")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(onlyExplicitlyIncluded = true)
public class Pev {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    @EqualsAndHashCode.Include
    @ToString.Include
    private UUID id;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "status", nullable = false, columnDefinition = "approval_status_t")
    @ToString.Include
    private ApprovalStatus status;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "approved_by")
    private User approvedBy;

    // Exatamente um entre citizen/establishment deve estar presente
    // (ver ck_pev_single_owner). Mapeado como 1:1 opcional em cada lado.
    @OneToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "citizen_id", unique = true)
    private Citizen citizen;

    @OneToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "establishment_id", unique = true)
    private Establishment establishment;

    @OneToOne(fetch = FetchType.LAZY, optional = false, cascade = {CascadeType.PERSIST, CascadeType.MERGE})
    @JoinColumn(name = "address_id", nullable = false, unique = true)
    private Address address;
}
