package com.demo.upimesh.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Simulated bank account. In a real system this would live in the bank's core,
 * not in our service. For the demo, we own the ledger.
 *
 * @Data generates: getters, setters, toString, equals, hashCode
 * @NoArgsConstructor generates the no-arg constructor JPA requires
 * The all-args constructor is kept manually so seedAccounts() stays readable.
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "accounts")
public class Account {

    @Id
    private String vpa; // Virtual Payment Address, e.g. "alice@demo"

    @Column(nullable = false)
    private String holderName;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balance;

    @Version  // Optimistic locking — prevents lost updates on concurrent transfers
    private Long version;

    public Account(String vpa, String holderName, BigDecimal balance) {
        this.vpa = vpa;
        this.holderName = holderName;
        this.balance = balance;
    }
}
