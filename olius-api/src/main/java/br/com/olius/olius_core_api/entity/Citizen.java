package br.com.olius.olius_core_api.entity;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
@Entity @Table(name="citizens") @Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
public class Citizen {
    @Id private UUID id;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(length=11, columnDefinition="char(11)", nullable=false) private String cpf;
    @Column(name="qr_token", length=64, nullable=false, updatable=false) private String qrToken;
    @Column(insertable=false, updatable=false) private long points;
    @OneToOne(fetch=FetchType.LAZY) @JoinColumn(name="id", insertable=false, updatable=false)
    private User user;
    public Citizen(UUID id, String cpf, String token) { this.id=id; this.cpf=cpf; this.qrToken=token; }
}
