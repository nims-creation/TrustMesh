package com.demo.upimesh.service;

import com.demo.upimesh.crypto.HybridCryptoService;
import com.demo.upimesh.crypto.ServerKeyHolder;
import com.demo.upimesh.model.Account;
import com.demo.upimesh.model.AccountRepository;
import com.demo.upimesh.model.MeshPacket;
import com.demo.upimesh.model.PaymentInstruction;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Helper service that:
 *   - seeds demo accounts on startup
 *   - simulates "sender phone creates an encrypted packet" flow
 *
 * Constructor injection: all three dependencies are final, making this
 * service's state predictable and the class easy to unit-test in isolation.
 */
@Service
public class DemoService {

    private static final Logger log = LoggerFactory.getLogger(DemoService.class);

    private final AccountRepository accounts;
    private final HybridCryptoService crypto;
    private final ServerKeyHolder serverKey;

    public DemoService(AccountRepository accounts,
                       HybridCryptoService crypto,
                       ServerKeyHolder serverKey) {
        this.accounts = accounts;
        this.crypto = crypto;
        this.serverKey = serverKey;
    }

    /**
     * Seeds 4 demo accounts on startup.
     *
     * @Profile("!prod") — this @PostConstruct is skipped in the prod profile.
     * In production, accounts come from the bank's core banking system
     * (or from a proper database migration via Flyway). Seeding fake data
     * in production would be a serious security and compliance issue.
     */
    @PostConstruct
    @Profile("!prod")
    public void seedAccounts() {
        if (accounts.count() == 0) {
            accounts.save(new Account("alice@demo", "Alice",   new BigDecimal("5000.00")));
            accounts.save(new Account("bob@demo",   "Bob",     new BigDecimal("1000.00")));
            accounts.save(new Account("carol@demo", "Carol",   new BigDecimal("2500.00")));
            accounts.save(new Account("dave@demo",  "Dave",    new BigDecimal("500.00")));
            log.info("Seeded 4 demo accounts");
        }
    }

    /**
     * Simulates the sender's phone:
     *   1. Build a PaymentInstruction with a fresh nonce + signedAt timestamp.
     *   2. Encrypt with the server's public key (hybrid RSA+AES).
     *   3. Wrap in a MeshPacket with TTL.
     *
     * In a real Android app, this exact code (minus the server-side reference)
     * would run on the phone. The phone would have already cached the server's
     * public key during a previous online session.
     */
    public MeshPacket createPacket(String senderVpa, String receiverVpa,
                                   BigDecimal amount, String pin, int ttl) throws Exception {
        PaymentInstruction instruction = new PaymentInstruction(
                senderVpa,
                receiverVpa,
                amount,
                // BCrypt: auto-generates a random salt + runs 2^10 hash iterations.
                // Raw SHA-256 (the previous approach) has NO salt — identical PINs
                // produce identical hashes, making rainbow table attacks trivial.
                BCrypt.hashpw(pin, BCrypt.gensalt(10)),
                UUID.randomUUID().toString(),
                Instant.now().toEpochMilli()
        );

        String ciphertext = crypto.encrypt(instruction, serverKey.getPublicKey());

        MeshPacket packet = new MeshPacket();
        packet.setPacketId(UUID.randomUUID().toString());
        packet.setTtl(ttl);
        packet.setCreatedAt(Instant.now().toEpochMilli());
        packet.setCiphertext(ciphertext);
        return packet;
    }

}
