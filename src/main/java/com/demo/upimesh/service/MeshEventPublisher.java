package com.demo.upimesh.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.LinkedHashMap;

/**
 * Publishes real-time mesh events to all connected WebSocket clients via STOMP.
 *
 * Clients subscribe to: /topic/mesh-events
 *
 * Event types pushed to the dashboard:
 *   PACKET_INJECTED     — a new encrypted packet entered the mesh
 *   GOSSIP_ROUND        — one gossip round completed (hop count + transfers)
 *   BRIDGE_UPLOAD       — a bridge node attempted to upload
 *   PACKET_SETTLED      — payment settled successfully (VPAs, amount, bridge)
 *   PACKET_DUPLICATE    — idempotency gate dropped a duplicate
 *   PACKET_INVALID      — decryption/tamper/freshness rejection
 *   MESH_RESET          — mesh state cleared
 *
 * The dashboard JS subscribes to this topic and appends events to the activity
 * log in real-time — no more 3-second polling!
 *
 * Each event payload is a Map serialized to JSON by Jackson automatically.
 */
@Slf4j
@Service
public class MeshEventPublisher {

    private static final String TOPIC = "/topic/mesh-events";

    private final SimpMessagingTemplate messaging;

    public MeshEventPublisher(SimpMessagingTemplate messaging) {
        this.messaging = messaging;
    }

    // ── Public event methods ──────────────────────────────────

    public void packetInjected(String packetId, String senderVpa, String receiverVpa,
                               double amount, int ttl, String deviceId) {
        publish("PACKET_INJECTED", map(
            "packetId",    truncate(packetId),
            "senderVpa",   senderVpa,
            "receiverVpa", receiverVpa,
            "amount",      amount,
            "ttl",         ttl,
            "deviceId",    deviceId,
            "icon",        "📤"
        ));
    }

    public void gossipRound(int transfers, Map<String, Integer> deviceCounts) {
        publish("GOSSIP_ROUND", map(
            "transfers",    transfers,
            "deviceCounts", deviceCounts,
            "icon",         "🔄"
        ));
    }

    public void bridgeUpload(String bridgeNodeId, String outcome, String reason) {
        publish("BRIDGE_UPLOAD", map(
            "bridgeNodeId", bridgeNodeId,
            "outcome",      outcome,
            "reason",       reason,
            "icon",         "📡"
        ));
    }

    public void packetSettled(String packetHash, String senderVpa, String receiverVpa,
                              double amount, String bridgeNodeId, int hopCount, Long txId) {
        publish("PACKET_SETTLED", map(
            "packetHash",   truncate(packetHash),
            "senderVpa",    senderVpa,
            "receiverVpa",  receiverVpa,
            "amount",       amount,
            "bridgeNodeId", bridgeNodeId,
            "hopCount",     hopCount,
            "txId",         txId,
            "icon",         "✅"
        ));
    }

    public void packetDuplicate(String packetHash, String bridgeNodeId) {
        publish("PACKET_DUPLICATE", map(
            "packetHash",   truncate(packetHash),
            "bridgeNodeId", bridgeNodeId,
            "icon",         "🚫"
        ));
    }

    public void packetInvalid(String reason, String bridgeNodeId) {
        publish("PACKET_INVALID", map(
            "reason",       reason,
            "bridgeNodeId", bridgeNodeId,
            "icon",         "❌"
        ));
    }

    public void meshReset() {
        publish("MESH_RESET", map("icon", "🗑"));
    }

    // ── Private helpers ───────────────────────────────────────

    private void publish(String type, Map<String, Object> payload) {
        payload.put("type",      type);
        payload.put("timestamp", Instant.now().toEpochMilli());
        try {
            messaging.convertAndSend(TOPIC, payload);
            log.debug("[ws] Published {} event", type);
        } catch (Exception e) {
            // Non-fatal — WebSocket failure must never break the payment pipeline
            log.warn("[ws] Failed to publish {} event: {}", type, e.getMessage());
        }
    }

    /** Build a LinkedHashMap (preserves insertion order for JSON) from key-value pairs. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object... kvPairs) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kvPairs.length - 1; i += 2) {
            m.put((String) kvPairs[i], kvPairs[i + 1]);
        }
        return m;
    }

    private static String truncate(String s) {
        return s != null && s.length() > 12 ? s.substring(0, 12) + "…" : s;
    }
}
