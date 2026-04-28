package com.demo.upimesh.controller;

import com.demo.upimesh.crypto.ServerKeyHolder;
import com.demo.upimesh.model.*;
import com.demo.upimesh.service.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.*;

/**
 * Public REST surface.
 *
 * The endpoints split into three groups:
 *   /api/server-key      → so simulated senders can fetch the server's public key
 *   /api/mesh/*          → simulator endpoints (inject, gossip, flush)
 *   /api/bridge/ingest   → THE real production endpoint a real bridge node would hit
 *   /api/accounts, /api/transactions → for the dashboard
 *
 * Constructor injection: all dependencies are final — Spring auto-wires them
 * because there is exactly one constructor (no @Autowired annotation needed).
 */
@RestController
@RequestMapping("/api")
@Tag(name = "Core API", description = "Primary endpoints for Mesh Injection, State Management, and Data Access")
public class ApiController {

    private final ServerKeyHolder serverKey;
    private final DemoService demo;
    private final MeshSimulatorService mesh;
    private final BridgeIngestionService bridge;
    private final AccountRepository accountRepo;
    private final TransactionRepository txRepo;
    private final IdempotencyService idempotency;

    public ApiController(ServerKeyHolder serverKey,
                         DemoService demo,
                         MeshSimulatorService mesh,
                         BridgeIngestionService bridge,
                         AccountRepository accountRepo,
                         TransactionRepository txRepo,
                         IdempotencyService idempotency) {
        this.serverKey = serverKey;
        this.demo = demo;
        this.mesh = mesh;
        this.bridge = bridge;
        this.accountRepo = accountRepo;
        this.txRepo = txRepo;
        this.idempotency = idempotency;
    }

    // ------------------------------------------------------------------ key

    @GetMapping("/server-key")
    @Operation(summary = "Get Server Public Key", description = "Returns the RSA-2048 public key used by sender devices to encrypt payment instructions before injecting into the mesh.")
    public Map<String, String> getServerPublicKey() {
        return Map.of(
                "publicKey", serverKey.getPublicKeyBase64(),
                "algorithm", "RSA-2048 / OAEP-SHA256",
                "hybridScheme", "RSA-OAEP encrypts an AES-256-GCM session key"
        );
    }

    // ---------------------------------------------------------------- demo

    /**
     * Demo helper: build a packet on the server (simulating a sender phone)
     * and inject it into the mesh at the given device.
     */
    @PostMapping("/demo/send")
    @Operation(summary = "Simulate Mesh Injection", description = "Creates an encrypted payment packet and injects it into a simulated device node. This mocks the Android app's sender behavior.")
    public ResponseEntity<?> demoSend(@RequestBody @Valid DemoSendRequest req) throws Exception {
        MeshPacket packet = demo.createPacket(
                req.senderVpa, req.receiverVpa, req.amount, req.pin,
                req.ttl == null ? 5 : req.ttl);

        String startDevice = req.startDevice == null ? "phone-alice" : req.startDevice;
        mesh.inject(startDevice, packet);

        return ResponseEntity.ok(Map.of(
                "packetId", packet.getPacketId(),
                "ciphertextPreview", packet.getCiphertext().substring(0, 64) + "...",
                "ttl", packet.getTtl(),
                "injectedAt", startDevice
        ));
    }

    public static class DemoSendRequest {
        @NotBlank(message = "senderVpa must not be blank")
        public String senderVpa;

        @NotBlank(message = "receiverVpa must not be blank")
        public String receiverVpa;

        @Positive(message = "amount must be positive")
        public java.math.BigDecimal amount;

        @NotBlank(message = "pin must not be blank")
        @Size(min = 4, max = 6, message = "pin must be 4-6 digits")
        public String pin;

        public Integer ttl;
        public String startDevice;
    }

    // -------------------------------------------------------------- mesh sim

    @GetMapping("/mesh/state")
    public Map<String, Object> meshState() {
        List<Map<String, Object>> deviceData = new ArrayList<>();
        for (VirtualDevice d : mesh.getDevices()) {
            deviceData.add(Map.of(
                    "deviceId", d.getDeviceId(),
                    "hasInternet", d.hasInternet(),
                    "packetCount", d.packetCount(),
                    "packetIds", d.getHeldPackets().stream()
                            .map(p -> p.getPacketId().substring(0, 8))
                            .toList()
            ));
        }
        return Map.of(
                "devices", deviceData,
                "idempotencyCacheSize", idempotency.size()
        );
    }

    @PostMapping("/mesh/gossip")
    public Map<String, Object> meshGossip() {
        MeshSimulatorService.GossipResult r = mesh.gossipOnce();
        return Map.of(
                "transfers", r.transfers(),
                "deviceCounts", r.deviceCounts()
        );
    }

    /**
     * "All bridge nodes simultaneously walk outside and get 4G."
     * They all upload everything they hold to /api/bridge/ingest.
     *
     * THIS is the moment the duplicate-storm idempotency case is tested:
     * if multiple bridge nodes hold the same packet, the server gets multiple
     * concurrent POSTs of the same ciphertext, and only one should settle.
     */
    @PostMapping("/mesh/flush")
    public Map<String, Object> meshFlush() {
        List<MeshSimulatorService.BridgeUpload> uploads = mesh.collectBridgeUploads();

        List<Map<String, Object>> results = new ArrayList<>();
        // Upload them in parallel to actually exercise concurrent idempotency.
        uploads.parallelStream().forEach(up -> {
            BridgeIngestionService.IngestResult r =
                    bridge.ingest(up.packet(), up.bridgeNodeId(), 5 - up.packet().getTtl());
            synchronized (results) {
                results.add(Map.of(
                        "bridgeNode", up.bridgeNodeId(),
                        "packetId", up.packet().getPacketId().substring(0, 8),
                        "outcome", r.outcome(),
                        "reason", r.reason() == null ? "" : r.reason(),
                        "transactionId", r.transactionId() == null ? -1 : r.transactionId()
                ));
            }
        });

        return Map.of(
                "uploadsAttempted", uploads.size(),
                "results", results
        );
    }

    @PostMapping("/mesh/reset")
    public Map<String, Object> meshReset() {
        mesh.resetMesh();
        idempotency.clear();
        return Map.of("status", "mesh and idempotency cache cleared");
    }

    // -------------------------------------------------------------- bridge

    /**
     * THE PRODUCTION ENDPOINT.
     * In a real deployment, the Android app's bridge logic POSTs here whenever
     * the device has internet and is holding mesh packets.
     */
    @PostMapping("/bridge/ingest")
    @Operation(summary = "Ingest Mesh Packet", description = "THE PRODUCTION ENDPOINT. Bridge nodes POST here whenever they reach internet connectivity and are holding mesh packets.")
    public ResponseEntity<?> ingest(
            @RequestBody @Valid MeshPacket packet,
            @RequestHeader(value = "X-Bridge-Node-Id", defaultValue = "unknown") String bridgeNodeId,
            @RequestHeader(value = "X-Hop-Count", defaultValue = "0") int hopCount) {

        BridgeIngestionService.IngestResult r = bridge.ingest(packet, bridgeNodeId, hopCount);
        return ResponseEntity.ok(r);
    }

    // ------------------------------------------------------------- accounts

    @GetMapping("/accounts")
    @Operation(summary = "List Accounts", description = "Returns all accounts and their balances for the dashboard.")
    public List<Account> listAccounts() {
        return accountRepo.findAll();
    }


    @GetMapping("/transactions")
    @Operation(summary = "List Transactions", description = "Returns the latest 50 settled transactions for the dashboard ledger.")
    public List<Transaction> listTransactions() {
        return txRepo.findTop50ByOrderByIdDesc();
    }

    @PostMapping("/accounts")
    @Operation(summary = "Create Account", description = "Creates a new demo account with a starting balance.")
    public ResponseEntity<?> createAccount(@RequestBody @Valid CreateAccountRequest req) {
        if (accountRepo.findById(req.vpa).isPresent()) {
            return ResponseEntity.badRequest().body(Map.of("error", "VPA already exists: " + req.vpa));
        }
        Account acc = new Account(req.vpa, req.holderName,
                new java.math.BigDecimal(req.initialBalance));
        accountRepo.save(acc);
        return ResponseEntity.ok(acc);
    }

    public static class CreateAccountRequest {
        @NotBlank public String vpa;
        @NotBlank public String holderName;
        @Positive public double initialBalance = 1000.0;
    }
}
