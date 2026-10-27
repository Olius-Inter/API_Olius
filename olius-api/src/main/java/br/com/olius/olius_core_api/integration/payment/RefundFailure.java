package br.com.olius.olius_core_api.integration.payment;

/** Falha técnica classificada, sem conservar corpo, causa HTTP ou credenciais do provedor. */
public final class RefundFailure extends RuntimeException {
    private final RefundGateway.FailureKind kind;
    public RefundFailure(RefundGateway.FailureKind kind) {
        super("REFUND_"+kind.name());this.kind=kind;
    }
    public RefundGateway.FailureKind kind() { return kind; }
}
