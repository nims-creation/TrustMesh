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
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;


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
    private final JwtService jwtService;

    public ApiController(ServerKeyHolder serverKey,
                         DemoService demo,
                         MeshSimulatorService mesh,
                         BridgeIngestionService bridge,
                         AccountRepository accountRepo,
                         TransactionRepository txRepo,
                         IdempotencyService idempotency,
                         JwtService jwtService) {
        this.serverKey  = serverKey;
        this.demo       = demo;
        this.mesh       = mesh;
        this.bridge     = bridge;
        this.accountRepo = accountRepo;
        this.txRepo      = txRepo;
        this.idempotency = idempotency;
        this.jwtService  = jwtService;
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
     * Bridge node registration — issues a signed JWT.
     *
     * Real bridge nodes call this endpoint once on app startup.
     * The JWT is stored on-device and sent with every /api/bridge/ingest call.
     * Token expiry (24h) matches UPI's offline transaction window.
     */
    @PostMapping("/bridge/register")
    @Operation(
        summary     = "Register Bridge Node",
        description = "Issues a signed JWT for a bridge node device. Include this token as 'Authorization: Bearer <token>' on all /api/bridge/ingest calls."
    )
    public ResponseEntity<?> registerBridgeNode(@RequestBody Map<String, String> body) {
        String deviceId = body.get("deviceId");
        if (deviceId == null || deviceId.isBlank()) {
            return ResponseEntity.badRequest().body(
                Map.of("error", "deviceId is required"));
        }
        String token = jwtService.issueToken(deviceId.trim());
        return ResponseEntity.ok(Map.of(
            "deviceId",   deviceId,
            "token",      token,
            "type",       "Bearer",
            "expiresIn",  "24h",
            "usage",      "Authorization: Bearer " + token.substring(0, 20) + "..."
        ));
    }

    /**
     * THE PRODUCTION ENDPOINT.
     * Requires Authorization: Bearer <JWT> from /api/bridge/register.
     * JwtAuthFilter validates the token before this method is called.
     * The authenticated deviceId is forwarded via request attribute.
     */
    @PostMapping("/bridge/ingest")
    @Operation(
        summary     = "Ingest Mesh Packet",
        description = "THE PRODUCTION ENDPOINT. Requires JWT auth (register at POST /api/bridge/register). Bridge nodes POST here when they reach internet connectivity."
    )
    public ResponseEntity<?> ingest(
            @RequestBody @Valid MeshPacket packet,
            @RequestAttribute(value = "authenticatedBridgeNodeId", required = false) String jwtDeviceId,
            @RequestHeader(value = "X-Bridge-Node-Id", defaultValue = "unknown") String headerDeviceId,
            @RequestHeader(value = "X-Hop-Count", defaultValue = "0") int hopCount) {

        // JWT deviceId takes precedence over header (JWT is authenticated, header is not)
        String bridgeNodeId = jwtDeviceId != null ? jwtDeviceId : headerDeviceId;
        BridgeIngestionService.IngestResult r = bridge.ingest(packet, bridgeNodeId, hopCount);
        return ResponseEntity.ok(r);
    }

    // ------------------------------------------------------------- accounts

    @GetMapping("/accounts")
    @Operation(summary = "List Accounts", description = "Returns all accounts and their balances for the dashboard.")
    public List<Account> listAccounts() {
        return accountRepo.findAll();
    }

    @GetMapping("/accounts/{vpa}")
    @Operation(summary = "Get Account by VPA", description = "Returns a single account by its Virtual Payment Address. Returns 404 if the VPA does not exist.")
    public ResponseEntity<?> getAccountByVpa(@PathVariable String vpa) {
        return accountRepo.findById(vpa)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/transactions")
    @Operation(summary = "List Transactions", description = "Returns the latest 50 settled transactions for the dashboard ledger.")
    public List<Transaction> listTransactions() {
        return txRepo.findTop50ByOrderByIdDesc();
    }

    // --------------------------------------------------------------- stats

    @GetMapping("/stats")
    @Operation(summary = "System Statistics", description = "Returns aggregated statistics: account count, transaction count, idempotency cache size, and mesh device summary.")
    public ResponseEntity<PacketStats> getStats() {
        long accountCount = StreamSupport.stream(accountRepo.findAll().spliterator(), false).count();
        long txCount = StreamSupport.stream(txRepo.findAll().spliterator(), false).count();
        int cacheSize = idempotency.size();
        int totalDevices = mesh.getDevices().size();
        long bridgeCount = mesh.getDevices().stream().filter(com.demo.upimesh.service.VirtualDevice::hasInternet).count();
        return ResponseEntity.ok(new PacketStats(accountCount, txCount, cacheSize, totalDevices, bridgeCount));
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

    // ---------------------------------------------------------- stress test

    @PostMapping("/demo/stress-test")
    @Operation(summary = "Idempotency Stress Test",
               description = "Creates a real encrypted packet then fires it at the backend from 3 bridge nodes SIMULTANEOUSLY using threads. Proves only exactly one debit happens regardless of concurrent uploads.")
    public ResponseEntity<?> stressTest() throws Exception {
        // 1. Create a real AES+RSA encrypted packet
        MeshPacket packet = demo.createPacket("alice@demo", "bob@demo",
                new java.math.BigDecimal("1"), "1234", 5);

        String ciphertextPreview = packet.getCiphertext().substring(0, 64) + "...";

        // 2. Fire 3 simultaneous bridge uploads using a thread pool
        ExecutorService pool = Executors.newFixedThreadPool(3);
        String[] bridges = {"bridge-alpha", "bridge-beta", "bridge-gamma"};
        List<Future<Map<String, String>>> futures = new ArrayList<>();

        for (String bridgeId : bridges) {
            futures.add(pool.submit(() -> {
                try {
                    BridgeIngestionService.IngestResult r = bridge.ingest(packet, bridgeId, 2);
                    return Map.of("bridge", bridgeId, "outcome", r.outcome(), "reason", r.reason() == null ? "" : r.reason());
                } catch (Exception e) {
                    return Map.of("bridge", bridgeId, "outcome", "ERROR", "reason", e.getMessage());
                }
            }));
        }
        pool.shutdown();

        // 3. Collect results
        List<Map<String, String>> results = futures.stream()
                .map(f -> { try { return f.get(); } catch (Exception e) { return Map.of("bridge", "?", "outcome", "ERROR", "reason", e.getMessage()); } })
                .collect(Collectors.toList());

        long settled = results.stream().filter(r -> "SETTLED".equals(r.get("outcome"))).count();
        long dropped = results.stream().filter(r -> "DUPLICATE_DROPPED".equals(r.get("outcome"))).count();

        return ResponseEntity.ok(Map.of(
                "explanation", "3 bridge nodes uploaded the SAME encrypted packet simultaneously. Only 1 was processed; the rest were blocked by the idempotency cache.",
                "ciphertextPreview", ciphertextPreview,
                "settled", settled,
                "duplicateDropped", dropped,
                "results", results
        ));
    }
}
