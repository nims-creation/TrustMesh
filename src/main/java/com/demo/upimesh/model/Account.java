package com.demo.upimesh.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Simulated bank account. In a real system this would live in the bank's core,
 * not in our service. For the demo, we own the ledger.
 *
 * Account lifecycle (real-bank pattern):
 *   ACTIVE  → the account is open and can send/receive
 *   CLOSED  → soft-deleted; data preserved for audit, no new transactions allowed
 *
 * @Data generates: getters, setters, toString, equals, hashCode
 * @NoArgsConstructor generates the no-arg constructor JPA requires
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "accounts")
public class Account {

    /** Lifecycle states — mirrors real NPCI account status codes. */
    public enum Status { ACTIVE, CLOSED }

    @Id
    private String vpa; // Virtual Payment Address, e.g. "alice@demo"

    @Column(nullable = false)
    private String holderName;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balance;

    @Version  // Optimistic locking — prevents lost updates on concurrent transfers
    private Long version;

    /**
     * Account lifecycle status.
     * ACTIVE  = open, can transact.
     * CLOSED  = permanently closed; balance should be 0, no new transactions.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.ACTIVE;

    /** Timestamp when this account was formally closed. Null if still ACTIVE. */
    @Column
    private Instant closedAt;

    /**
     * Free-text reason provided at account closure time.
     * Required by RBI guidelines for audit trail in real banking.
     */
    @Column(length = 500)
    private String closeReason;

    /** Timestamp when this account was first registered in the system. */
    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public Account(String vpa, String holderName, BigDecimal balance) {
        this.vpa = vpa;
        this.holderName = holderName;
        this.balance = balance;
        this.status = Status.ACTIVE;
        this.createdAt = Instant.now();
    }

    /** Convenience: is this account open for transactions? */
    public boolean isActive() {
        return status == Status.ACTIVE;
    }
}
