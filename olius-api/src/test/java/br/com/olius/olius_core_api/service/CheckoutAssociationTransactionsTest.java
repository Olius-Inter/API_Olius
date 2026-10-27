package br.com.olius.olius_core_api.service;

import br.com.olius.olius_core_api.exception.ResourceConflictException;
import br.com.olius.olius_core_api.integration.payment.CheckoutGateway.CheckoutSession;
import br.com.olius.olius_core_api.repository.BillingCheckoutRepository;
import br.com.olius.olius_core_api.repository.BillingCheckoutRepository.Charge;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class CheckoutAssociationTransactionsTest {
    private final BillingCheckoutRepository bills=mock(BillingCheckoutRepository.class);
    private final AuditContext audit=mock(AuditContext.class);
    private final CheckoutAssociationTransactions transactions=new CheckoutAssociationTransactions(bills,audit);
    private final UUID id=UUID.randomUUID(),owner=UUID.randomUUID(),plan=UUID.randomUUID(),key=UUID.randomUUID();
    private final Instant now=Instant.parse("2026-10-26T12:00:00Z"),expires=now.plusSeconds(3600);
    private final CheckoutSession external=new CheckoutSession("cs_test_fixture",URI.create("https://checkout.stripe.com/c/pay/test"),expires);
    @BeforeEach void setup() {
        when(bills.activeOwner(owner)).thenReturn(true);
        when(bills.now()).thenReturn(now);
        when(bills.lockCharge(id)).thenReturn(List.of(charge("19.90")));
    }
    private Charge charge(String amount) {
        return new Charge(id,owner,plan,key,"Plano",new BigDecimal(amount),expires,"OPEN",null,"STRIPE","INITIAL");
    }
    @ParameterizedTest @ValueSource(strings={"19.9","19.900","19.9000"})
    void associatesNumericallyEqualAmountsWithDifferentScales(String amount) {
        var expected=charge(amount);
        transactions.associate(owner,expected,external);
        verify(bills).associate(id,external.providerId());
        verify(audit).user(owner,"Associação do Checkout Stripe à cobrança");
    }
    @Test void rejectsDifferentNumericalAmountWithoutAssociatingSession() {
        var expected=charge("19.91");
        assertThatThrownBy(()->transactions.associate(owner,expected,external)).isInstanceOf(ResourceConflictException.class);
        verify(bills,never()).associate(any(),anyString());verifyNoInteractions(audit);
    }
    @Test void preservesExpirationValidationEvenWithAnEqualAmount() {
        var expected=charge("19.900");
        var changed=new CheckoutSession(external.providerId(),external.paymentUrl(),expires.plusSeconds(1));
        assertThatThrownBy(()->transactions.associate(owner,expected,changed)).isInstanceOf(ResourceConflictException.class);
        verify(bills,never()).associate(any(),anyString());verifyNoInteractions(audit);
    }
}
