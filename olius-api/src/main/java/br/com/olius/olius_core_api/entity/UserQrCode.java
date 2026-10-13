package br.com.olius.olius_core_api.entity;
import jakarta.persistence.*;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
@Entity @Table(name="user_qr_code") @NoArgsConstructor(access=AccessLevel.PROTECTED)
public class UserQrCode {
    @Id @Column(name="user_id") private UUID userId;
    @Column(name="qr_token", length=64, nullable=false) private String qrToken;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name="user_type", columnDefinition="user_type_t", nullable=false, updatable=false)
    private UserType userType;
    @OneToOne(fetch=FetchType.LAZY) @JoinColumn(name="user_id", insertable=false, updatable=false)
    private User user;
    public UserQrCode(UUID userId, String token, UserType type) {
        this.userId=userId; this.qrToken=token; this.userType=type;
    }
}
