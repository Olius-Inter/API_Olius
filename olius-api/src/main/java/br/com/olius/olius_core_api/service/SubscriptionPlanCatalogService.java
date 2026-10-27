package br.com.olius.olius_core_api.service;

import br.com.olius.olius_core_api.dto.SubscriptionPlanResponse;
import br.com.olius.olius_core_api.entity.enums.UserStatus;
import br.com.olius.olius_core_api.repository.SubscriptionPlanCatalogRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SubscriptionPlanCatalogService {
    private final SubscriptionPlanCatalogRepository plans;
    public SubscriptionPlanCatalogService(SubscriptionPlanCatalogRepository plans) { this.plans=plans; }

    @Transactional(readOnly=true)
    public List<SubscriptionPlanResponse> activePlans() {
        return plans.findByStatusOrderByMonthlyPriceAscIdAsc(UserStatus.ACTIVE).stream()
                .map(plan -> new SubscriptionPlanResponse(plan.getId(),plan.getName(),plan.getDescription(),
                        plan.getMonthlyPrice(),plan.getVolumeLimitLiters(),plan.getCollectionLimit()))
                .toList();
    }
}
