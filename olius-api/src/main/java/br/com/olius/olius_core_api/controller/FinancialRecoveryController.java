package br.com.olius.olius_core_api.controller;

import br.com.olius.olius_core_api.dto.FinancialRecoveryContracts.*;
import br.com.olius.olius_core_api.security.Caller;
import br.com.olius.olius_core_api.service.FinancialRecoveryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/financial-recovery")
@PreAuthorize("hasRole('ADMIN')")
public class FinancialRecoveryController {
    private final FinancialRecoveryService service;
    public FinancialRecoveryController(FinancialRecoveryService service) { this.service=service; }
    /** Lista cobranças abertas para investigação administrativa; tamanho máximo de cinquenta itens. */
    @GetMapping("/charges") public Page<Charge> charges(Authentication auth,@RequestParam(required=false) UUID after,@RequestParam(defaultValue="20") @Min(1) @Max(50) int size) {
        return service.charges(Caller.id(auth),after,size);
    }
    /** Consulta os dados técnicos de uma cobrança, inclusive se já estiver encerrada. */
    @GetMapping("/charges/{id}") public Charge charge(Authentication auth,@PathVariable UUID id) { return service.charge(Caller.id(auth),id); }
    /** Solicita nova verificação conservadora; não aceita identidade ou estado financeiro escolhidos pelo cliente. */
    @PostMapping("/charges/{id}/reconciliation") public Charge reconcileCharge(Authentication auth,@PathVariable UUID id,@Valid @RequestBody ChargeAction action) {
        return service.reconcileCharge(Caller.id(auth),id,action.reason());
    }
    /** Lista devoluções ainda não concluídas, com cursor estável e sem documentos pessoais. */
    @GetMapping("/refunds") public Page<Refund> refunds(Authentication auth,@RequestParam(required=false) UUID after,@RequestParam(defaultValue="20") @Min(1) @Max(50) int size) {
        return service.refunds(Caller.id(auth),after,size);
    }
    /** Consulta detalhes e tentativas de uma devolução autorizada pelo banco. */
    @GetMapping("/refunds/{id}") public Refund refund(Authentication auth,@PathVariable UUID id) { return service.refund(Caller.id(auth),id); }
    /** Confere um reembolso existente na Stripe com justificativa e autoria derivada do Bearer. */
    @PostMapping("/refunds/{id}/reconciliation") public Refund reconcileRefund(Authentication auth,@PathVariable UUID id,@Valid @RequestBody RefundAction action) {
        return service.reconcileRefund(Caller.id(auth),id,action.providerRefundId(),action.reason());
    }
}
