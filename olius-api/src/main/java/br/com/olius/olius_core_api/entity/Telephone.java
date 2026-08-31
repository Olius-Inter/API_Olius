package br.com.olius.olius_core_api.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

/**
 * Tabela: telephone
 *
 * Relação N:1 com User (um usuário pode ter vários telefones; cada
 * telefone pertence a exatamente um usuário — ON DELETE RESTRICT).
 * Unidirecional: User não mantém List<Telephone> (evita coleção
 * potencialmente carregada sem necessidade; buscar via repositório
 * quando preciso).
 *
 * Observação de negócio: telephone.telephone é UNIQUE globalmente,
 * ou seja, o mesmo número não pode estar associado a mais de um usuário
 * no sistema.
 */
@Entity
@Table(name = "telephone")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(onlyExplicitlyIncluded = true)
public class Telephone {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    @EqualsAndHashCode.Include
    @ToString.Include
    private UUID id;

    @Column(name = "telephone", nullable = false, unique = true, length = 20)
    @ToString.Include
    private String telephone;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;
}
