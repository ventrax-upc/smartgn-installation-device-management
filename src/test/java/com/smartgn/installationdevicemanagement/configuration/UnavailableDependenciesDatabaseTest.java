package com.smartgn.installationdevicemanagement.configuration;

import static org.junit.jupiter.api.Assertions.*;

import com.smartgn.installationdevicemanagement.InstallationDeviceManagementApplication;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Objects;
import java.util.UUID;

/**
 * Exercises unavailable dependency configuration against an isolated local PostgreSQL schema,
 * without external services.
 */
@org.springframework.context.annotation.Import(TestIdentityConfiguration.class)
@SpringBootTest(
        classes = InstallationDeviceManagementApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("unavailable-dependencies-test")
@EnabledIfEnvironmentVariable(named = "SMARTGN_TEST_DATABASE_URL", matches = ".+")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class UnavailableDependenciesDatabaseTest {
    private static final String SCHEMA =
            "installation_dependencies_test_" + UUID.randomUUID().toString().replace("-", "");
    private static final String SECRET = "dependency-test-secret-0123456789-abcdefghijklmnop";
    private final HttpClient client =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    @Value("${local.server.port}")
    private int port;

    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry values) {
        values.add("spring.datasource.url", () -> System.getenv("SMARTGN_TEST_DATABASE_URL"));
        values.add("spring.datasource.username", UnavailableDependenciesDatabaseTest::databaseUser);
        values.add(
                "spring.datasource.password",
                UnavailableDependenciesDatabaseTest::databasePassword);
        values.add(
                "spring.datasource.hikari.connection-init-sql",
                () -> "SET search_path TO " + SCHEMA);
        values.add("spring.flyway.schemas", () -> SCHEMA);
        values.add("spring.flyway.default-schema", () -> SCHEMA);
        values.add("smartgn.security.jwt-secret", () -> SECRET);
        values.add(
                "smartgn.devices.credential-key",
                () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        values.add("spring.data.redis.port", () -> 1);
    }

    @AfterAll
    static void cleanupSchema() {
        if (!SCHEMA.matches("installation_dependencies_test_[a-f0-9]{32}"))
            throw new IllegalStateException("Unsafe test schema");
        var database =
                new DriverManagerDataSource(
                        System.getenv("SMARTGN_TEST_DATABASE_URL"),
                        databaseUser(),
                        databasePassword());
        new JdbcTemplate(database).execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
    }

    @Test
    void healthAndHttpsSwaggerWorkWithoutRedisBrokerOrTelemetry() throws Exception {
        var health = send("GET", "/actuator/health", null, null);
        assertEquals(200, health.statusCode(), health.body());
        assertEquals("UP", mapper.readTree(health.body()).get("status").asText());
        var swagger =
                client.send(
                        HttpRequest.newBuilder(URI.create(baseUrl() + "/v3/api-docs"))
                                .header("X-Forwarded-Proto", "https")
                                .header("X-Forwarded-Host", "installation.example")
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertEquals(200, swagger.statusCode(), swagger.body());
        assertEquals(
                "https://installation.example",
                mapper.readTree(swagger.body()).get("servers").get(0).get("url").asText());
    }

    @Test
    void databaseBackedPropertyOperationsRemainAvailable() throws Exception {
        String token = token("USER");
        var response =
                send(
                        "POST",
                        "/api/v1/properties",
                        token,
                        "{\"name\":\"Local test home\",\"address\":\"Test Street"
                                + " 123\",\"propertyType\":\"HOUSE\"}");
        assertEquals(201, response.statusCode(), response.body());
        String id = mapper.readTree(response.body()).get("id").asText();
        assertEquals(200, send("GET", "/api/v1/properties/" + id, token, null).statusCode());
        assertEquals(401, send("GET", "/api/v1/properties", null, null).statusCode());
    }

    @Test
    void disabledDeviceProvisioningReturns503WithoutPersistingDevicesOrJobs() throws Exception {
        var response =
                send(
                        "POST",
                        "/api/v1/devices",
                        token("SUPERADMIN"),
                        "{\"deviceId\":\""
                                + UUID.randomUUID()
                                + "\",\"supplyPointId\":\""
                                + UUID.randomUUID()
                                + "\",\"installerId\":\""
                                + UUID.randomUUID()
                                + "\",\"installationId\":\""
                                + UUID.randomUUID()
                                + "\",\"serialNumber\":\"local-test-meter\","
                                + "\"location\":\"Kitchen\",\"installedAt\":\""
                                + Instant.now().minusSeconds(60)
                                + "\"}");
        assertEquals(503, response.statusCode(), response.body());
        assertEquals(
                "DEPENDENCY_UNAVAILABLE", mapper.readTree(response.body()).get("code").asText());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM devices", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM outbox_jobs", Integer.class));
    }

    private static String token(String role) {
        String account = UUID.randomUUID().toString();
        return Jwts.builder()
                .audience()
                .add("smartgn-api")
                .and()
                .subject(account)
                .issuer("smartgn-iam-service")
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plusSeconds(300)))
                .claim("accountId", account)
                .claim("role", role)
                .claim("plan", "FREE")
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }

    private HttpResponse<String> send(String method, String path, String token, String body)
            throws Exception {
        var request =
                HttpRequest.newBuilder(URI.create(baseUrl() + path))
                        .timeout(Duration.ofSeconds(15));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body != null) request.header("Content-Type", "application/json");
        request.method(
                method,
                body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    private static String databaseUser() {
        return Objects.requireNonNullElse(
                System.getenv("SMARTGN_TEST_DATABASE_USERNAME"), "postgres");
    }

    private static String databasePassword() {
        return Objects.requireNonNullElse(System.getenv("SMARTGN_TEST_DATABASE_PASSWORD"), "");
    }
}
