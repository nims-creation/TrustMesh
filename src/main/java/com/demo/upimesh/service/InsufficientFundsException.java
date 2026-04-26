package com.demo.upimesh.service;

/**
 * Thrown by SettlementService when the sender's balance is too low.
 *
 * Why a custom exception instead of silent REJECTED return?
 *   - Callers (BridgeIngestionService) can distinguish a business rejection
 *     (insufficient funds) from an internal error (DB down, NPE, etc.)
 *   - Allows Spring's @ExceptionHandler to map it to a proper HTTP 422 response
 *   - Makes the failure mode explicit in the method signature instead of
 *     hiding it inside a Transaction.Status enum value
 *
 * It extends RuntimeException so @Transactional rolls back automatically.
 */
public class InsufficientFundsException extends RuntimeException {

    private final String senderVpa;
    private final java.math.BigDecimal available;
    private final java.math.BigDecimal requested;

    public InsufficientFundsException(String senderVpa,
                                      java.math.BigDecimal available,
                                      java.math.BigDecimal requested) {
        super(String.format("Insufficient funds for %s: has ₹%s, tried to send ₹%s",
                senderVpa, available, requested));
        this.senderVpa = senderVpa;
        this.available = available;
        this.requested = requested;
    }

    public String getSenderVpa() { return senderVpa; }
    public java.math.BigDecimal getAvailable() { return available; }
    public java.math.BigDecimal getRequested() { return requested; }
}
