package br.com.olius.olius_core_api.entity;
import jakarta.persistence.*;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
@Entity @Table(name="telephone") @Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
public class Telephone {
    @Id private UUID id;
    @Column(length=20, nullable=false) private String telephone;
    @Column(name="user_id", nullable=false, updatable=false) private UUID userId;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="user_id", insertable=false, updatable=false) private User user;
    public Telephone(UUID userId, String number) { id=UUID.randomUUID(); this.userId=userId; telephone=number; }
}
