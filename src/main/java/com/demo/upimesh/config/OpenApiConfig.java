package com.demo.upimesh.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for Swagger/OpenAPI documentation.
 * This configures the title, version, and description shown at the top of the /swagger-ui.html page.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI trustMeshOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("TrustMesh - UPI Offline Mesh API")
                        .description("Backend APIs for simulating an offline, Bluetooth mesh-based UPI payment system. Handles hybrid encryption, idempotency, and replay-attack protection.")
                        .version("v1.0.0")
                        .contact(new Contact()
                                .name("TrustMesh Developer")
                                .url("https://github.com/nims-creation/TrustMesh")
                        )
                        .license(new License().name("MIT License").url("https://opensource.org/licenses/MIT")));
    }
}
