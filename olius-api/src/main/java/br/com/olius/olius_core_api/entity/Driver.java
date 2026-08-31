package br.com.olius.olius_core_api.entity;

import br.com.olius.olius_core_api.enums.ActiveStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Tabela: driver
 *
 * Entidade independente (não é subtipo de User). Motoristas autenticam-se
 * de forma separada dos usuários do app cidadão/estabelecimento — vale
 * confirmar essa premissa com o time, pois não há FK de driver para users.
 *
 * Regra de negócio ck_driver_updated_at (updated_at >= registration_date)
 * é garantida no banco via CHECK; não é replicável de forma direta em
 * anotação JPA. @UpdateTimestamp já garante updated_at >= registration_date
 * na prática (updated_at sempre "anda para frente" a partir da criação).
 */
@Entity
@Table(name = "driver")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(onlyExplicitlyIncluded = true)
public class Driver {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    @EqualsAndHashCode.Include
    @ToString.Include
    private UUID id;

    @Column(name = "name", nullable = false, length = 150)
    @ToString.Include
    private String name;

    @Column(name = "cpf", nullable = false, unique = true, length = 11, columnDefinition = "CHAR(11)")
    private String cpf;

    @Column(name = "cnh", nullable = false, unique = true, length = 11, columnDefinition = "CHAR(11)")
    private String cnh;

    @CreationTimestamp
    @Column(name = "registration_date", nullable = false, updatable = false)
    private LocalDateTime registrationDate;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "status", nullable = false, columnDefinition = "active_status_t")
    @ToString.Include
    private ActiveStatus status;
}
