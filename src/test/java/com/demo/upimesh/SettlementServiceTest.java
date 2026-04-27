package com.demo.upimesh;

import com.demo.upimesh.model.Account;
import com.demo.upimesh.model.AccountRepository;
import com.demo.upimesh.model.PaymentInstruction;
import com.demo.upimesh.model.TransactionRepository;
import com.demo.upimesh.service.SettlementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

/**
 * Pure unit test for SettlementService.
 * Because we use constructor injection, we don't need the Spring context (@SpringBootTest),
 * making these tests blazingly fast.
 */
@ExtendWith(MockitoExtension.class)
public class SettlementServiceTest {

    @Mock
    private AccountRepository accountRepo;

    @Mock
    private TransactionRepository txRepo;

    private SettlementService settlementService;

    @BeforeEach
    void setUp() {
        settlementService = new SettlementService(accountRepo, txRepo);
    }

    @Test
    void testNegativeAmountThrowsIllegalArgumentException() {
        // Arrange
        Account sender = new Account("alice@demo", "Alice", new BigDecimal("5000.00"));
        Account receiver = new Account("bob@demo", "Bob", new BigDecimal("100.00"));

        when(accountRepo.findById("alice@demo")).thenReturn(Optional.of(sender));
        when(accountRepo.findById("bob@demo")).thenReturn(Optional.of(receiver));

        PaymentInstruction badInstruction = new PaymentInstruction(
                "alice@demo",
                "bob@demo",
                new BigDecimal("-50.00"), // Negative amount!
                "hash",
                "nonce",
                123456L
        );

        // Act & Assert
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> {
            settlementService.settle(badInstruction, "packetHash", "bridge-1", 3);
        });

        assertEquals("Amount must be positive", ex.getMessage());
    }

    @Test
    void testZeroAmountThrowsIllegalArgumentException() {
        // Arrange
        Account sender = new Account("alice@demo", "Alice", new BigDecimal("5000.00"));
        Account receiver = new Account("bob@demo", "Bob", new BigDecimal("100.00"));

        when(accountRepo.findById("alice@demo")).thenReturn(Optional.of(sender));
        when(accountRepo.findById("bob@demo")).thenReturn(Optional.of(receiver));

        PaymentInstruction badInstruction = new PaymentInstruction(
                "alice@demo",
                "bob@demo",
                BigDecimal.ZERO, // Zero amount!
                "hash",
                "nonce",
                123456L
        );

        // Act & Assert
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> {
            settlementService.settle(badInstruction, "packetHash", "bridge-1", 3);
        });

        assertEquals("Amount must be positive", ex.getMessage());
    }
}
