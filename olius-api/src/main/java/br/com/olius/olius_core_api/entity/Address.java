package br.com.olius.olius_core_api.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Tabela: addresses
 *
 * Entidade "neutra": tanto {@link Establishment} quanto {@link Pev} podem
 * possuir, cada um, um endereço próprio e exclusivo (FK UNIQUE em ambos os
 * lados). Por isso Address NÃO conhece seu dono — um relacionamento
 * bidirecional aqui seria ambíguo (poderia pertencer a um Establishment OU
 * a um Pev) e desnecessário. A associação é sempre unidirecional, partindo
 * de quem possui o endereço.
 */
@Entity
@Table(name = "addresses")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(onlyExplicitlyIncluded = true)
public class Address {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    @EqualsAndHashCode.Include
    @ToString.Include
    private UUID id;

    @Column(name = "state", nullable = false, length = 2, columnDefinition = "CHAR(2)")
    @ToString.Include
    private String state;

    @Column(name = "city", nullable = false, length = 100)
    @ToString.Include
    private String city;

    @Column(name = "neighborhood", nullable = false, length = 100)
    private String neighborhood;

    @Column(name = "street", nullable = false, length = 150)
    private String street;

    // VARCHAR no banco (não é numérico): existem números como "S/N", "123A" etc.
    @Column(name = "number", nullable = false, length = 20)
    private String number;

    @Column(name = "cep", nullable = false, length = 8, columnDefinition = "CHAR(8)")
    @ToString.Include
    private String cep;

    @Column(name = "complement", length = 150)
    private String complement;

    @Column(name = "latitude", precision = 9, scale = 6)
    private BigDecimal latitude;

    @Column(name = "longitude", precision = 9, scale = 6)
    private BigDecimal longitude;
}
