package br.com.olius.olius_core_api.service;

import br.com.olius.olius_core_api.dto.CheckoutResponse;
import br.com.olius.olius_core_api.exception.PaymentProviderUnavailableException;
import br.com.olius.olius_core_api.integration.payment.CheckoutGateway;
import br.com.olius.olius_core_api.integration.payment.CheckoutGateway.CheckoutCharge;
import br.com.olius.olius_core_api.repository.BillingCheckoutRepository.Charge;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Compartilha o transporte e a associação, preservando as regras próprias de cada contratação. */
@Service
public class CheckoutSessionService {
    private final ObjectProvider<CheckoutGateway> gateways;
    private final CheckoutAssociationTransactions associations;
    public CheckoutSessionService(ObjectProvider<CheckoutGateway> gateways,CheckoutAssociationTransactions associations) {
        this.gateways=gateways;this.associations=associations;
    }
    public CheckoutGateway requireGateway() {
        var gateway=gateways.getIfAvailable();
        if(gateway==null) throw new PaymentProviderUnavailableException();
        return gateway;
    }
    @Transactional(propagation=Propagation.NEVER)
    public CheckoutResponse link(UUID owner,Charge stored,CheckoutGateway gateway) {
        var request=new CheckoutCharge(stored.id(),stored.key(),stored.planName(),stored.amount(),stored.expiresAt());
        var session=stored.providerId()==null ? gateway.create(request) : gateway.retrieve(request,stored.providerId());
        associations.associate(owner,stored,session);
        return new CheckoutResponse(stored.id(),stored.planId(),stored.amount(),"BRL",session.expiresAt(),session.paymentUrl());
    }
}
