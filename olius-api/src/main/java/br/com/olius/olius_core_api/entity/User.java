package br.com.olius.olius_core_api.entity;

import br.com.olius.olius_core_api.enums.UserType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Tabela: users
 *
 * Tabela "raiz" do padrão de especialização (table-per-subtype com PK
 * compartilhada): {@link Citizen} e {@link Establishment} reutilizam o
 * mesmo id de users como sua própria PK/FK (ver @MapsId nessas classes).
 * O valor ADMIN de user_type NÃO possui tabela de extensão — nesse caso
 * o próprio registro em users já é a entidade completa.
 *
 * DECISÃO: User não mantém referências de volta para Citizen/Establishment/
 * Telephone (sem coleções bidirecionais "por conveniência"). Quem precisar
 * navegar nesse sentido deve fazer isso explicitamente via repositório,
 * já que nem todo User possui uma dessas extensões (ADMIN) e não seria
 * possível modelar isso de forma segura como uma única associação.
 */
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(onlyExplicitlyIncluded = true)
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    @EqualsAndHashCode.Include
    @ToString.Include
    private UUID id;

    @Column(name = "name", nullable = false, length = 150)
    @ToString.Include
    private String name;

    @Column(name = "email", nullable = false, unique = true, length = 255)
    @ToString.Include
    private String email;

    // Nunca exposto em toString/logs.
    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "user_type", nullable = false, columnDefinition = "user_type_t")
    @ToString.Include
    private UserType userType;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
