package com.smartgn.installationdevicemanagement.configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfiguration {
    @Bean
    OpenAPI installationOpenApi() {
        return new OpenAPI()
                .info(
                        new Info()
                                .title("SmartGN Installation & Device Management API")
                                .version("1.0")
                                .description(
                                        "Properties, supply points, external IoT installers,"
                                            + " provisional and paid installations, versioned"
                                            + " device associations and confirmed provisioning. IAM"
                                            + " authorizes current delegated functions; Billing,"
                                            + " Support and Monitoring remain separate services."))
                .addSecurityItem(
                        new io.swagger.v3.oas.models.security.SecurityRequirement()
                                .addList("bearerAuth"))
                .components(
                        new Components()
                                .addSecuritySchemes(
                                        "serviceAuth",
                                        new SecurityScheme()
                                                .type(SecurityScheme.Type.HTTP)
                                                .scheme("bearer")
                                                .description(
                                                        "Private service credential. Internal paths"
                                                            + " must remain outside public gateway"
                                                            + " routes."))
                                .addSecuritySchemes(
                                        "bearerAuth",
                                        new SecurityScheme()
                                                .type(SecurityScheme.Type.HTTP)
                                                .scheme("bearer")
                                                .bearerFormat("JWT")
                                                .description(
                                                        "IAM JWT with issuer, smartgn-api audience,"
                                                            + " expiry and current account/session"
                                                            + " checks. Roles: USER, SUPERADMIN."
                                                            + " Meter permissions are checked"
                                                            + " live.")));
    }
}
