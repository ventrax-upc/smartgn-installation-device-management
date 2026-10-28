package com.smartgn.installationdevicemanagement.configuration;

import com.smartgn.installationdevicemanagement.shared.application.port.out.IdentityGateway;
import com.smartgn.installationdevicemanagement.shared.domain.Role;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;

import java.util.*;

/** Explicit HTTP-test double. End-to-end tests use the real IAM service. */
@TestConfiguration
public class TestIdentityConfiguration {
    @Bean
    @Primary
    IdentityGateway testIdentity(@Value("${smartgn.security.jwt-secret}") String secret) {
        return new IdentityGateway() {
            public Account resolve(String token) {
                var c =
                        io.jsonwebtoken.Jwts.parser()
                                .verifyWith(
                                        io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                                                secret.getBytes(
                                                        java.nio.charset.StandardCharsets.UTF_8)))
                                .build()
                                .parseSignedClaims(token)
                                .getPayload();
                return new Account(
                        UUID.fromString(c.getSubject()),
                        "test@example.test",
                        Role.valueOf(c.get("role", String.class)),
                        "PRINCIPAL",
                        "ACTIVE",
                        null);
            }

            public Account account(UUID id) {
                return new Account(id, "test@example.test", Role.USER, "PRINCIPAL", "ACTIVE", null);
            }

            public boolean authorize(UUID a, UUID m, String f) {
                return false;
            }

            public Map<UUID, Set<String>> meters(UUID a) {
                return Map.of();
            }
        };
    }
}
