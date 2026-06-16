package com.demo.upimesh.service;

import com.demo.upimesh.model.Account;
import com.demo.upimesh.model.AccountRepository;
import com.demo.upimesh.model.PaymentInstruction;
import com.demo.upimesh.model.Transaction;
import com.demo.upimesh.model.TransactionRepository;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Where the actual ledger update happens. Wrapped in a DB transaction so either
 * BOTH the debit and credit happen, or neither does.
 *
 * Resilience patterns applied:
 *
 * @Retry("settlementRetry"):
 *   Retries up to 3 times with 200ms delay on transient DB errors
 *   (JpaSystemException, OptimisticLockingFailureException).
 *   DOES NOT retry InsufficientFundsException — that is a business decision,
 *   not a transient error. Retrying would double-debit.
 *
 * @CircuitBreaker("settlementCB"):
 *   Opens after 50% failure rate in a 10-call sliding window.
 *   In OPEN state, calls are rejected immediately with CallNotPermittedException —
 *   preventing DB failure cascades from overwhelming the connection pool.
 *   Transitions to HALF-OPEN after 10s, allowing 3 probe calls.
 *   If probes succeed → CLOSED. If probes fail → OPEN again.
 *
 * Why Retry wraps CircuitBreaker (not the other way around):
 *   The recommended order is: Retry → CircuitBreaker → @Transactional → DB.
 *   Each retry attempt is a fresh circuit breaker call — the breaker counts
 *   the final failure (after all retries) as one failure, not each retry.
 *
 * Why @Version (Optimistic Locking) is the last line of defence:
 *   Idempotency gate (L1) → CircuitBreaker (L2) → Retry (L3) → @Transactional + @Version (L4)
 *   Defence in depth: four independent layers prevent any double-debit.
 */
@Service
@Slf4j
public class SettlementService {

    private final AccountRepository accounts;
    private final TransactionRepository transactions;

    public SettlementService(AccountRepository accounts, TransactionRepository transactions) {
        this.accounts     = accounts;
        this.transactions = transactions;
    }

    /**
     * Settles a payment instruction atomically.
     *
     * Resilience order: @Retry wraps @CircuitBreaker wraps @Transactional.
     * Fallback: on circuit open, returns a CIRCUIT_OPEN transaction stub
     * so BridgeIngestionService can return an informative INVALID response
     * rather than throwing an unhandled exception.
     */
    @Retry(name = "settlementRetry")
    @CircuitBreaker(name = "settlementCB", fallbackMethod = "settleFallback")
    @Transactional
    public Transaction settle(PaymentInstruction instruction, String packetHash,
                              String bridgeNodeId, int hopCount) {

        Account sender = accounts.findById(instruction.getSenderVpa())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown sender VPA: " + instruction.getSenderVpa()));

        Account receiver = accounts.findById(instruction.getReceiverVpa())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown receiver VPA: " + instruction.getReceiverVpa()));

        BigDecimal amount = instruction.getAmount();
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }

        if (sender.getBalance().compareTo(amount) < 0) {
            log.warn("Insufficient balance: {} has ₹{}, tried to send ₹{}",
                    sender.getVpa(), sender.getBalance(), amount);
            throw new InsufficientFundsException(sender.getVpa(), sender.getBalance(), amount);
        }

        sender.setBalance(sender.getBalance().subtract(amount));
        receiver.setBalance(receiver.getBalance().add(amount));
        accounts.save(sender);
        accounts.save(receiver);

        Transaction tx = new Transaction();
        tx.setPacketHash(packetHash);
        tx.setSenderVpa(instruction.getSenderVpa());
        tx.setReceiverVpa(instruction.getReceiverVpa());
        tx.setAmount(amount);
        tx.setSignedAt(Instant.ofEpochMilli(instruction.getSignedAt()));
        tx.setSettledAt(Instant.now());
        tx.setBridgeNodeId(bridgeNodeId);
        tx.setHopCount(hopCount);
        tx.setStatus(Transaction.Status.SETTLED);
        transactions.save(tx);

        log.info("SETTLED ₹{} from {} to {} (packetHash={}, bridge={}, hops={})",
                amount, sender.getVpa(), receiver.getVpa(),
                packetHash.substring(0, 12) + "...", bridgeNodeId, hopCount);

        return tx;
    }

    /**
     * Circuit breaker fallback — called when the circuit is OPEN.
     *
     * Returns a sentinel Transaction with status CIRCUIT_OPEN so that
     * BridgeIngestionService can log it and return INVALID to the client
     * with a meaningful reason, instead of propagating a raw exception.
     */
    public Transaction settleFallback(PaymentInstruction instruction, String packetHash,
                                      String bridgeNodeId, int hopCount, Throwable cause) {
        log.error("[circuit-breaker] Settlement circuit OPEN — rejecting packet {}… Cause: {}",
                packetHash.substring(0, 12), cause.getMessage());

        Transaction stub = new Transaction();
        stub.setPacketHash(packetHash);
        stub.setSenderVpa(instruction.getSenderVpa());
        stub.setReceiverVpa(instruction.getReceiverVpa());
        stub.setAmount(instruction.getAmount());
        stub.setSignedAt(Instant.now());
        stub.setSettledAt(Instant.now());
        stub.setBridgeNodeId(bridgeNodeId);
        stub.setHopCount(hopCount);
        stub.setStatus(Transaction.Status.CIRCUIT_OPEN);
        return stub;
    }
}
