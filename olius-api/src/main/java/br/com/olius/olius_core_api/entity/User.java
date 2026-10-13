package br.com.olius.olius_core_api.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name="users")
@Getter
@NoArgsConstructor(access=AccessLevel.PROTECTED)
public class User {
    @Id
    private UUID id;

    @Column(nullable=false, length=150)
    private String name;

    @Column(nullable=false, length=255)
    private String email;

    @Column(name="password_hash", nullable=false, length=255)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name="user_type", columnDefinition="user_type_t", nullable=false, updatable=false)
    private UserType userType;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(columnDefinition="active_status_t", insertable=false, updatable=false)
    private UserStatus status;

    @Column(name="created_at", insertable=false, updatable=false)
    private Instant createdAt;

    @Column(name="updated_at", insertable=false, updatable=false)
    private Instant updatedAt;

    public User(UUID id, String name, String email, String passwordHash, UserType type) {
        this.id=id; this.name=name; this.email=email; this.passwordHash=passwordHash; this.userType=type;
    }
}
