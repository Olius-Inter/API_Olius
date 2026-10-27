package br.com.olius.olius_core_api.repository;

import br.com.olius.olius_core_api.entity.SubscriptionPlan;
import br.com.olius.olius_core_api.entity.enums.UserStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/** Sem save/delete: manutenção comercial não é responsabilidade desta consulta. */
public interface SubscriptionPlanCatalogRepository extends Repository<SubscriptionPlan,UUID> {
    List<SubscriptionPlan> findByStatusOrderByMonthlyPriceAscIdAsc(UserStatus status);
}
