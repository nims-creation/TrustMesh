package com.demo.upimesh.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * Configures STOMP over SockJS WebSocket messaging.
 *
 * Architecture:
 *   Client connects to:  ws://localhost:8080/ws  (SockJS fallback: HTTP long-poll)
 *   Server publishes to: /topic/mesh-events       (broadcast to all subscribers)
 *   Client subscribes:   /topic/mesh-events
 *
 * Why STOMP over raw WebSocket?
 *   - STOMP provides a lightweight publish/subscribe protocol on top of WebSocket
 *   - SockJS provides transparent fallback to HTTP long-polling for environments
 *     that block WebSocket (corporate proxies, some load balancers)
 *   - Spring's SimpMessagingTemplate makes server-side publishing a single method call
 *
 * Why in-memory broker (not RabbitMQ/Kafka)?
 *   - Demo runs without external dependencies ("clone and run")
 *   - For production scale, replace SimpleBroker with a full broker relay:
 *     config.enableStompBrokerRelay("amqp://rabbitmq:5672")
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // Enable in-memory simple broker for /topic/** destinations
        config.enableSimpleBroker("/topic");

        // All messages from clients addressed to /app/** are routed to @MessageMapping methods
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // SockJS endpoint: clients connect to /ws
        // SockJS provides transparent HTTP fallback for non-WebSocket environments
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")  // permissive for demo; restrict in prod
                .withSockJS();
    }
}
