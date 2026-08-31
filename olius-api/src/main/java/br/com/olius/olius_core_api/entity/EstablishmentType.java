package br.com.olius.olius_core_api.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

/**
 * Tabela: establishment_type
 *
 * Tabela de domínio/lookup simples. Não há necessidade de coleção
 * bidirecional List<Establishment> aqui — quem precisar listar os
 * estabelecimentos de um tipo deve consultar via repositório
 * (EstablishmentRepository.findByType), evitando carregar coleções
 * potencialmente grandes dentro da entidade de domínio.
 */
@Entity
@Table(name = "establishment_type")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(onlyExplicitlyIncluded = true)
public class EstablishmentType {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    @EqualsAndHashCode.Include
    @ToString.Include
    private UUID id;

    @Column(name = "name", nullable = false, unique = true, length = 100)
    @ToString.Include
    private String name;

    @Column(name = "description", length = 255)
    private String description;
}
