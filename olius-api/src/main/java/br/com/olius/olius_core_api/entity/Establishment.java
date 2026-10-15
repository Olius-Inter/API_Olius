package br.com.olius.olius_core_api.entity;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
@Entity @Table(name="establishment") @Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
public class Establishment {
    @Id private UUID id;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(length=14, columnDefinition="char(14)", nullable=false) private String cnpj;
    @Column(name="qr_token", length=64, nullable=false, updatable=false) private String qrToken;
    @Column(insertable=false, updatable=false) private long points;
    @Column(columnDefinition="text") private String description;
    @Column(name="is_pev", insertable=false, updatable=false) private boolean pev;
    @Column(name="type_id", nullable=false) private UUID typeId;
    @Column(name="address_id", nullable=false) private UUID addressId;
    @OneToOne(fetch=FetchType.LAZY) @JoinColumn(name="id", insertable=false, updatable=false) private User user;
    @OneToOne(fetch=FetchType.LAZY) @JoinColumn(name="address_id", insertable=false, updatable=false) private Address address;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="type_id", insertable=false, updatable=false) private EstablishmentType type;
    public Establishment(UUID id, String cnpj, String token, String description, UUID typeId, UUID addressId) {
        this.id=id; this.cnpj=cnpj; this.qrToken=token; this.description=description;
        this.typeId=typeId; this.addressId=addressId;
    }
}
