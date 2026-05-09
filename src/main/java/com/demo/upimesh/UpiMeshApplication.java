package com.demo.upimesh;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;

/**
 * TrustMesh application entry point.
 *
 * <p>Prints a friendly startup banner to the console when the application is
 * fully initialised so developers immediately know which URLs to open.
 */
@SpringBootApplication
public class UpiMeshApplication {

    private static final Logger log = LoggerFactory.getLogger(UpiMeshApplication.class);

    private final Environment env;

    public UpiMeshApplication(Environment env) {
        this.env = env;
    }

    public static void main(String[] args) {
        SpringApplication.run(UpiMeshApplication.class, args);
    }

    /**
     * Fires once the application context is fully refreshed and the embedded
     * server is accepting requests. Logs the dashboard and Swagger URLs so
     * developers can navigate directly without hunting for the port number.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        String port = env.getProperty("local.server.port", "8080");
        log.info("╔══════════════════════════════════════════════════╗");
        log.info("║          TrustMesh is ready! 🚀                  ║");
        log.info("║  Dashboard  → http://localhost:{}/              ║", port);
        log.info("║  Swagger    → http://localhost:{}/swagger-ui.html ║", port);
        log.info("║  Health     → http://localhost:{}/api/health     ║", port);
        log.info("║  Stats      → http://localhost:{}/api/stats      ║", port);
        log.info("╚══════════════════════════════════════════════════╝");
    }
}
