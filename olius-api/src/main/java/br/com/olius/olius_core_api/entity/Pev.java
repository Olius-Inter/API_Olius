package br.com.olius.olius_core_api.entity;

import br.com.olius.olius_core_api.entity.enums.PevStatus;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name="pev")
@Getter
@NoArgsConstructor(access=AccessLevel.PROTECTED)
public class Pev {
    @Id private UUID id;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(columnDefinition="approval_status_t", insertable=false, updatable=false)
    private PevStatus status;
    @Column(name="created_at", insertable=false, updatable=false) private Instant createdAt;
    @Column(name="updated_at", insertable=false, updatable=false) private Instant updatedAt;
    @Column(name="approved_at", insertable=false, updatable=false) private Instant approvedAt;
    @Column(name="approved_by", insertable=false, updatable=false) private UUID approvedBy;
    @Column(name="citizen_id", updatable=false) private UUID citizenId;
    @Column(name="establishment_id", updatable=false) private UUID establishmentId;
    @Column(name="address_id", nullable=false, updatable=false) private UUID addressId;
    @OneToOne(fetch=FetchType.LAZY) @JoinColumn(name="address_id", insertable=false, updatable=false)
    private Address address;

    public Pev(UUID id, UUID citizenId, UUID establishmentId, UUID addressId) {
        this.id=id;
        this.citizenId=citizenId;
        this.establishmentId=establishmentId;
        this.addressId=addressId;
    }
}
