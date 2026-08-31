package br.com.olius.olius_core_api.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

/**
 * Tabela: citizens
 *
 * Especialização de User via PK compartilhada (id de citizens == id de
 * users). Mapeada com @OneToOne + @MapsId, o padrão canônico do JPA para
 * "chave primária derivada": Citizen não gera seu próprio id, ele é
 * herdado do User associado no momento da criação.
 *
 * DECISÃO: sem CascadeType aqui. O User referenciado precisa já existir
 * (criação em duas etapas: cria User, depois cria Citizen apontando pra
 * ele), o que é consistente com o ON DELETE RESTRICT da FK no banco
 * (não é permitido remover um User que já tenha uma extensão Citizen).
 *
 * O campo "points" é uma desnormalização intencional (saldo corrente);
 * o histórico granular fica em delivery_pev.points_earned. Por isso ele
 * é mantido como uma coluna simples e mutável — a lógica de quem
 * incrementa/decrementa esse saldo pertence à camada de serviço (ou a
 * uma trigger no banco), não a este mapeamento.
 */
@Entity
@Table(name = "citizens")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(onlyExplicitlyIncluded = true)
public class Citizen {

    @Id
    @Column(name = "id")
    @EqualsAndHashCode.Include
    @ToString.Include
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId
    @JoinColumn(name = "id")
    private User user;

    @Column(name = "cpf", nullable = false, unique = true, length = 11, columnDefinition = "CHAR(11)")
    private String cpf;

    @Column(name = "qr_token", nullable = false, unique = true, length = 64)
    private String qrToken;

    @Builder.Default
    @Column(name = "points", nullable = false)
    @ToString.Include
    private Integer points = 0;
}
