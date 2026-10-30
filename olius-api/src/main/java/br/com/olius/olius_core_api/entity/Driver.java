package br.com.olius.olius_core_api.entity;

import br.com.olius.olius_core_api.entity.enums.UserStatus;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Motorista operacional: não é usuário e não possui credenciais de login. */
@Entity @Table(name="driver") @Getter @DynamicUpdate
@NoArgsConstructor(access=AccessLevel.PROTECTED)
public class Driver {
    @Id private UUID id;
    @Column(nullable=false,length=150) private String name;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable=false,length=11,columnDefinition="char(11)") private String cpf;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable=false,length=11,columnDefinition="char(11)") private String cnh;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(columnDefinition="active_status_t",insertable=false) private UserStatus status;
    @Column(name="registration_date",insertable=false,updatable=false) private Instant registrationDate;
    @Column(name="updated_at",insertable=false,updatable=false) private Instant updatedAt;

    public Driver(UUID id,String name,String cpf,String cnh) {
        this.id=id;this.name=name;this.cpf=cpf;this.cnh=cnh;
    }
    public void update(String name,String cpf,String cnh) {
        if(name!=null) this.name=name;
        if(cpf!=null) this.cpf=cpf;
        if(cnh!=null) this.cnh=cnh;
    }
    public void changeStatus(UserStatus status) { this.status=status; }
}
