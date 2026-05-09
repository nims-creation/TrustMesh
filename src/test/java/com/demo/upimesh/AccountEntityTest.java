package com.demo.upimesh;

import com.demo.upimesh.model.Account;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the {@link Account} entity.
 *
 * These tests do NOT start a Spring context — they exercise the plain Java
 * object to verify field initialisation, constructor convenience, and the
 * presence of the optimistic-locking version field.
 */
@DisplayName("Account entity unit tests")
class AccountEntityTest {

    @Test
    @DisplayName("All-args constructor sets fields correctly")
    void allArgsConstructor_setsFieldsCorrectly() {
        Account acc = new Account("alice@demo", "Alice Kumar", new BigDecimal("5000.00"));

        assertThat(acc.getVpa()).isEqualTo("alice@demo");
        assertThat(acc.getHolderName()).isEqualTo("Alice Kumar");
        assertThat(acc.getBalance()).isEqualByComparingTo("5000.00");
    }

    @Test
    @DisplayName("createdAt is set to approximately now by all-args constructor")
    void allArgsConstructor_setsCreatedAtToNow() {
        Instant before = Instant.now();
        Account acc = new Account("bob@demo", "Bob Singh", BigDecimal.TEN);
        Instant after = Instant.now();

        assertThat(acc.getCreatedAt()).isNotNull();
        assertThat(acc.getCreatedAt()).isBetween(before, after);
    }

    @Test
    @DisplayName("No-args constructor initialises createdAt to current time")
    void noArgsConstructor_initialises_createdAt() {
        Instant before = Instant.now();
        Account acc = new Account();
        acc.setVpa("carol@demo");
        acc.setHolderName("Carol Verma");
        acc.setBalance(BigDecimal.ZERO);
        Instant after = Instant.now();

        assertThat(acc.getCreatedAt()).isBetween(before, after);
    }

    @Test
    @DisplayName("Version field defaults to null before persistence")
    void versionField_isNullBeforePersistence() {
        Account acc = new Account("dave@demo", "Dave Sharma", BigDecimal.ONE);
        // @Version is managed by JPA; before persist it should be null
        assertThat(acc.getVersion()).isNull();
    }

    @Test
    @DisplayName("Setter updates balance correctly")
    void setBalance_updatesValue() {
        Account acc = new Account("eve@demo", "Eve Patel", new BigDecimal("100.00"));
        acc.setBalance(new BigDecimal("250.50"));
        assertThat(acc.getBalance()).isEqualByComparingTo("250.50");
    }
}
