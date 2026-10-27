package br.com.olius.olius_core_api.service;

import br.com.olius.olius_core_api.exception.PaymentProviderUnavailableException;
import br.com.olius.olius_core_api.integration.payment.CheckoutGateway;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class InitialCheckoutServiceTest {
    @Test @SuppressWarnings("unchecked")
    void disabledProviderDoesNotStartAnyDatabaseOperation() {
        var transactions=mock(InitialCheckoutTransactions.class);
        ObjectProvider<CheckoutGateway> gateways=mock(ObjectProvider.class);
        var service=new InitialCheckoutService(transactions,new CheckoutSessionService(gateways,mock(CheckoutAssociationTransactions.class)));
        assertThatThrownBy(()->service.create(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID()))
                .isInstanceOf(PaymentProviderUnavailableException.class);
        verifyNoInteractions(transactions);
    }
}
