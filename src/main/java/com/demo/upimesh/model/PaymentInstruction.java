package com.demo.upimesh.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * The actual payment instruction. After the server decrypts MeshPacket.ciphertext,
 * it gets one of these.
 *
 * Critical fields for security:
 *   - nonce: a UUID unique to this payment. Even if everything else were identical
 *            for two legitimate payments (alice sends bob ₹100 twice), the nonces
 *            differ, so the resulting ciphertexts and their hashes also differ.
 *   - signedAt: lets the server reject stale packets ("freshness window"). Without
 *               this, an attacker who got the ciphertext could replay it weeks later.
 *   - pinHash: in a real system the user enters a UPI PIN; we'd verify it against
 *              a hash held by the bank. Here we just record it for realism.
 *
 * @Data       — getters/setters/equals/hashCode/toString
 * @NoArgsConstructor  — required by Jackson for JSON deserialization (decrypt path)
 * @AllArgsConstructor — used by DemoService.createPacket() for clean construction
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "The decrypted payment instruction payload hidden inside the MeshPacket's ciphertext.")
public class PaymentInstruction {

    @Schema(description = "Virtual Payment Address of the sender.", example = "alice@demo")
    private String senderVpa;
    
    @Schema(description = "Virtual Payment Address of the receiver.", example = "bob@demo")
    private String receiverVpa;
    
    @Schema(description = "The payment amount.", example = "150.50")
    private BigDecimal amount;
    
    @Schema(description = "BCrypt hash of the user's UPI PIN.", example = "$2a$10$wT8hO...")
    private String pinHash;
    
    @Schema(description = "A UUID unique to this specific payment intent. Ensures that two identical payments generate different ciphertexts.", example = "b2c3d4e5-6789-01fa-bcde-fghijklmnopqrst")
    private String nonce;     // UUID, unique per payment intent
    
    @Schema(description = "Epoch milliseconds when the sender created and signed the instruction. Used for replay attack prevention (freshness window).", example = "1700000000000")
    private Long signedAt;    // epoch millis, when sender signed
}
