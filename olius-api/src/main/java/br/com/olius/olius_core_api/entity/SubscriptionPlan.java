package br.com.olius.olius_core_api.entity;

import br.com.olius.olius_core_api.entity.enums.UserStatus;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Oferta comercial; esta etapa disponibiliza somente consulta do catálogo. */
@Entity
@Table(name="subscription_plan")
@Immutable
@Getter
@NoArgsConstructor(access=AccessLevel.PROTECTED)
public class SubscriptionPlan {
    @Id private UUID id;
    @Column(nullable=false,length=100) private String name;
    @Column(columnDefinition="text") private String description;
    @Column(name="monthly_price",nullable=false,precision=10,scale=2) private BigDecimal monthlyPrice;
    @Column(name="volume_limit_liters",nullable=false,precision=8,scale=2) private BigDecimal volumeLimitLiters;
    @Column(name="collection_limit",nullable=false) private Integer collectionLimit;
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable=false,columnDefinition="active_status_t") private UserStatus status;
}
