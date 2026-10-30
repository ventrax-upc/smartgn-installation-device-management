package com.ventrax.installation.configuration;

import static org.junit.jupiter.api.Assertions.*;

import com.ventrax.installation.InstallationDeviceManagementApplication;
import com.ventrax.installation.shared.application.port.out.InstallationStore;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Real HTTP, Spring Security, validation, Flyway and PostgreSQL without touching the main schema.
 */
@org.springframework.context.annotation.Import(TestIdentityConfiguration.class)
@SpringBootTest(
        classes = InstallationDeviceManagementApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "SMARTGN_TEST_DATABASE_URL", matches = ".+")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class InstallationHttpDatabaseTest {
    private static final String SCHEMA =
            "installation_http_test_" + UUID.randomUUID().toString().replace("-", "");
    private static final String JWT_SECRET = "http-integration-test-secret-0123456789-abcdef";
    private static final String ISSUER = "smartgn-iam-service";
    private final HttpClient client =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    @Value("${local.server.port}")
    private int port;

    @Autowired private ObjectMapper mapper;
    @Autowired private InstallationStore store;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry values) {
        String url = System.getenv("SMARTGN_TEST_DATABASE_URL");
        values.add(
                "spring.datasource.url",
                () -> url + (url.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA);
        values.add("spring.datasource.username", InstallationHttpDatabaseTest::databaseUser);
        values.add("spring.datasource.password", InstallationHttpDatabaseTest::databasePassword);
        values.add(
                "spring.datasource.hikari.connection-init-sql",
                () -> "SET search_path TO " + SCHEMA);
        values.add("spring.flyway.schemas", () -> SCHEMA);
        values.add("spring.flyway.default-schema", () -> SCHEMA);
        values.add("smartgn.worker.enabled", () -> "false");
        values.add("smartgn.assignment.enabled", () -> "false");
        values.add("smartgn.security.jwt-secret", () -> JWT_SECRET);
        values.add("smartgn.security.jwt-issuer", () -> ISSUER);
        values.add("smartgn.security.jwt-audience", () -> "");
        values.add("smartgn.security.allowed-origins", () -> "http://localhost:4200");
        values.add(
                "smartgn.devices.credential-key",
                () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        values.add("smartgn.telemetry.base-url", () -> "http://127.0.0.1:1");
        values.add("smartgn.telemetry.service-token", () -> "integration-test-internal-token");
        values.add("smartgn.broker.base-url", () -> "http://127.0.0.1:1/api/v5");
        values.add("smartgn.broker.api-key", () -> "integration-test-api-key");
        values.add("smartgn.broker.api-secret", () -> "integration-test-api-secret");
        values.add("smartgn.broker.password", () -> "integration-test-broker-password");
        values.add("spring.data.redis.host", () -> "127.0.0.1");
        values.add("spring.data.redis.port", () -> "1");
    }

    private static String databaseUser() {
        return Objects.requireNonNullElse(
                System.getenv("SMARTGN_TEST_DATABASE_USERNAME"), "postgres");
    }

    private static String databasePassword() {
        return Objects.requireNonNullElse(System.getenv("SMARTGN_TEST_DATABASE_PASSWORD"), "");
    }

    @AfterAll
    static void cleanupSchema() {
        if (!SCHEMA.matches("installation_http_test_[a-f0-9]{32}"))
            throw new IllegalStateException("Unsafe test schema");
        var administrative =
                new DriverManagerDataSource(
                        System.getenv("SMARTGN_TEST_DATABASE_URL"),
                        databaseUser(),
                        databasePassword());
        new JdbcTemplate(administrative).execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
    }

    @Test
    void actualSpringBootContextServesPublicOpenApiWithBearerSecurity() throws Exception {
        assertEquals("4.1.1", SpringBootVersion.getVersion());
        HttpResponse<String> response = request("GET", "/v3/api-docs", null, null, null);
        assertEquals(200, response.statusCode(), response.body());
        JsonNode document = mapper.readTree(response.body());
        assertTrue(document.get("paths").has("/api/v1/properties"));
        assertTrue(document.get("components").get("securitySchemes").has("bearerAuth"));
    }

    @Test
    void validIamJwtBindsOwnershipAndAnotherAccountCannotReadOrModifyProperty() throws Exception {
        UUID owner = UUID.randomUUID(), foreign = UUID.randomUUID();
        String token = token(owner, Instant.now().plusSeconds(300));
        JsonNode property = createProperty(token);
        assertEquals(owner.toString(), property.get("accountId").asText());
        String id = property.get("id").asText();
        assertEquals(
                200, request("GET", "/api/v1/properties/" + id, token, null, null).statusCode());
        String foreignToken = token(foreign, Instant.now().plusSeconds(300));
        assertProblem(request("GET", "/api/v1/properties/" + id, foreignToken, null, null), 403);
        assertProblem(
                request("PUT", "/api/v1/properties/" + id, foreignToken, propertyUpdate(0), null),
                403);
        assertEquals(owner, store.findProperty(UUID.fromString(id)).orElseThrow().accountId());
    }

    @Test
    void missingExpiredAndWronglySignedTokensCannotAccessProtectedApi() throws Exception {
        UUID owner = UUID.randomUUID();
        assertProblem(request("GET", "/api/v1/properties", null, null, null), 401);
        assertProblem(
                request(
                        "GET",
                        "/api/v1/properties",
                        token(owner, Instant.now().minusSeconds(5)),
                        null,
                        null),
                401);
        String otherSecret = "untrusted-issuer-secret-0123456789-abcdef";
        String wrong =
                Jwts.builder()
                        .audience()
                        .add("smartgn-api")
                        .and()
                        .subject(owner.toString())
                        .issuer(ISSUER)
                        .issuedAt(Date.from(Instant.now().minusSeconds(30)))
                        .expiration(Date.from(Instant.now().plusSeconds(300)))
                        .claim("accountId", owner.toString())
                        .claim("role", "USER")
                        .claim("plan", "FREE")
                        .signWith(Keys.hmacShaKeyFor(otherSecret.getBytes(StandardCharsets.UTF_8)))
                        .compact();
        assertProblem(request("GET", "/api/v1/properties", wrong, null, null), 401);
    }

    @Test
    void malformedJsonMissingRequiredParameterAndInvalidDtoProduce400() throws Exception {
        String token = token(UUID.randomUUID(), Instant.now().plusSeconds(300));
        JsonNode property = createProperty(token);
        assertProblem(request("POST", "/api/v1/properties", token, "{ malformed", null), 400);
        assertProblem(request("POST", "/api/v1/properties", token, "{}", null), 400);
        assertProblem(
                request(
                        "DELETE",
                        "/api/v1/properties/" + property.get("id").asText(),
                        token,
                        null,
                        null),
                400);
        assertProblem(request("GET", "/api/v1/properties/not-a-uuid", token, null, null), 400);
        assertTrue(
                store.findProperty(UUID.fromString(property.get("id").asText()))
                        .orElseThrow()
                        .active());
    }

    @Test
    void stalePropertyDtoVersionReturns409AndDoesNotOverwriteCurrentValue() throws Exception {
        String token = token(UUID.randomUUID(), Instant.now().plusSeconds(300));
        JsonNode property = createProperty(token);
        String path = "/api/v1/properties/" + property.get("id").asText();
        HttpResponse<String> updated = request("PUT", path, token, propertyUpdate(0), null);
        assertEquals(200, updated.statusCode(), updated.body());
        assertEquals(1, mapper.readTree(updated.body()).get("version").asLong());
        assertProblem(request("PUT", path, token, propertyUpdate(0), null), 409);
        assertEquals(
                1,
                store.findProperty(UUID.fromString(property.get("id").asText()))
                        .orElseThrow()
                        .version());
    }

    private JsonNode createProperty(String token) throws Exception {
        String body =
                mapper.writeValueAsString(
                        Map.of(
                                "name",
                                "HTTP test home",
                                "address",
                                "Integration Street 123",
                                "propertyType",
                                "HOUSE"));
        HttpResponse<String> response = request("POST", "/api/v1/properties", token, body, null);
        assertEquals(201, response.statusCode(), response.body());
        return mapper.readTree(response.body());
    }

    private String propertyUpdate(long version) {
        return mapper.writeValueAsString(
                Map.of(
                        "name",
                        "Updated HTTP home",
                        "address",
                        "Integration Street 123",
                        "propertyType",
                        "HOUSE",
                        "version",
                        version));
    }

    private static String token(UUID account, Instant expiration) {
        return Jwts.builder()
                .audience()
                .add("smartgn-api")
                .and()
                .subject(account.toString())
                .issuer(ISSUER)
                .issuedAt(Date.from(Instant.now().minusSeconds(30)))
                .expiration(Date.from(expiration))
                .claim("accountId", account.toString())
                .claim("role", "USER")
                .claim("plan", "FREE")
                .signWith(Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }

    private HttpResponse<String> request(
            String method, String path, String token, String body, String correlation)
            throws Exception {
        HttpRequest.Builder builder =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .timeout(Duration.ofSeconds(path.equals("/v3/api-docs") ? 60 : 15));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (correlation != null) builder.header("X-Correlation-ID", correlation);
        if (body != null) builder.header("Content-Type", "application/json");
        builder.method(
                method,
                body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void assertProblem(HttpResponse<String> response, int expected) {
        assertEquals(expected, response.statusCode(), response.body());
        assertTrue(
                response.headers()
                        .firstValue("Content-Type")
                        .orElse("")
                        .startsWith("application/problem+json"),
                response.body());
        assertEquals(expected, mapper.readTree(response.body()).get("status").asInt());
    }

    @Test
    void collectionPaginationIsBoundedStableAndAccountScoped() throws Exception {
        UUID account = UUID.randomUUID();
        String owner = token(account, Instant.now().plusSeconds(300));
        for (int i = 0; i < 3; i++) createProperty(owner);
        JsonNode first =
                mapper.readTree(
                        request("GET", "/api/v1/properties?offset=0&limit=2", owner, null, null)
                                .body());
        JsonNode second =
                mapper.readTree(
                        request("GET", "/api/v1/properties?offset=2&limit=2", owner, null, null)
                                .body());
        assertEquals(2, first.size());
        assertEquals(1, second.size());
        assertFalse(first.get(0).get("id").equals(second.get(0).get("id")));
        assertEquals(
                first,
                mapper.readTree(
                        request("GET", "/api/v1/properties?offset=0&limit=2", owner, null, null)
                                .body()));
        assertProblem(request("GET", "/api/v1/properties?limit=101", owner, null, null), 400);
        assertProblem(request("GET", "/api/v1/properties?offset=-1", owner, null, null), 400);
        String foreign = token(UUID.randomUUID(), Instant.now().plusSeconds(300));
        assertEquals(
                0,
                mapper.readTree(
                                request("GET", "/api/v1/properties?limit=100", foreign, null, null)
                                        .body())
                        .size());
    }

    @Test
    void cancellationAndReassignmentUseCurrentVersionAndTheHttpCorrelation() throws Exception {
        String owner = token(UUID.randomUUID(), Instant.now().plusSeconds(300)),
                admin = roleToken(UUID.randomUUID(), "SUPERADMIN");
        JsonNode property = createProperty(owner);
        UUID correlation = UUID.randomUUID();
        JsonNode point =
                body(
                        request(
                                "POST",
                                "/api/v1/properties/"
                                        + property.get("id").asText()
                                        + "/supply-points",
                                owner,
                                mapper.writeValueAsString(
                                        Map.of(
                                                "serialNumber",
                                                UUID.randomUUID().toString(),
                                                "locationName",
                                                "Kitchen")),
                                correlation.toString()),
                        201);
        UUID pointId = UUID.fromString(point.get("id").asText());
        assertEquals(
                correlation, store.listAudit("SUPPLY_POINT", pointId).getFirst().correlationId());
        String create =
                mapper.writeValueAsString(
                        Map.of(
                                "propertyId",
                                property.get("id").asText(),
                                "pointId",
                                pointId.toString()));
        JsonNode installation = paidFixture(property, point);
        UUID id = UUID.fromString(installation.get("id").asText());
        String path = "/api/v1/installations/" + id;
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        store.saveInstaller(
                new com.ventrax.installation.shared.domain.InstallationModels.Installer(
                        first,
                        "First",
                        "Installer",
                        first.toString(),
                        "123",
                        null,
                        true,
                        0,
                        "fixture-" + UUID.randomUUID(),
                        true),
                -1);
        store.saveInstaller(
                new com.ventrax.installation.shared.domain.InstallationModels.Installer(
                        second,
                        "Second",
                        "Installer",
                        second.toString(),
                        "123",
                        null,
                        true,
                        0,
                        "fixture-" + UUID.randomUUID(),
                        true),
                -1);
        body(
                request(
                        "POST",
                        path + "/assign",
                        admin,
                        mapper.writeValueAsString(Map.of("installerId", first)),
                        correlation.toString()),
                200);
        String change =
                mapper.writeValueAsString(
                        Map.of(
                                "installerId",
                                second,
                                "version",
                                1,
                                "reason",
                                "Installer unavailable"));
        body(request("PATCH", path + "/reassign", admin, change, correlation.toString()), 200);
        assertProblem(request("PATCH", path + "/reassign", admin, change, null), 409);
        String cancel =
                mapper.writeValueAsString(Map.of("version", 2, "reason", "Owner cancelled visit"));
        assertProblem(request("POST", path + "/cancel", owner, cancel, null), 403);
        JsonNode cancelled =
                body(request("POST", path + "/cancel", admin, cancel, correlation.toString()), 200);
        assertEquals("CANCELLED", cancelled.get("status").asText());
        assertEquals(cancelled, body(request("POST", path + "/cancel", admin, cancel, null), 200));
        assertTrue(
                store.listAudit("INSTALLATION", id).stream()
                        .allMatch(a -> a.correlationId().equals(correlation)));
        paidFixture(property, point);
    }

    @Test
    void registrationCorrelatesAuditAndOutboxWithoutExposingSecretPayloads() throws Exception {
        String owner = token(UUID.randomUUID(), Instant.now().plusSeconds(300)),
                admin = roleToken(UUID.randomUUID(), "SUPERADMIN");
        JsonNode property = createProperty(owner);
        UUID correlation = UUID.randomUUID();
        JsonNode point =
                body(
                        request(
                                "POST",
                                "/api/v1/properties/"
                                        + property.get("id").asText()
                                        + "/supply-points",
                                owner,
                                mapper.writeValueAsString(
                                        Map.of(
                                                "serialNumber",
                                                UUID.randomUUID().toString(),
                                                "locationName",
                                                "Kitchen")),
                                null),
                        201);
        JsonNode installation = paidFixture(property, point);
        UUID installer = UUID.randomUUID();
        store.saveInstaller(
                new com.ventrax.installation.shared.domain.InstallationModels.Installer(
                        installer,
                        "IoT",
                        "Installer",
                        installer.toString(),
                        "123",
                        null,
                        true,
                        0,
                        "fixture-" + UUID.randomUUID(),
                        true),
                -1);
        String path = "/api/v1/installations/" + installation.get("id").asText();
        body(
                request(
                        "POST",
                        path + "/assign",
                        admin,
                        mapper.writeValueAsString(Map.of("installerId", installer)),
                        null),
                200);
        body(request("PATCH", path + "/status", admin, "{\"status\":\"IN_PROGRESS\"}", null), 200);
        JsonNode receipt =
                body(
                        request(
                                "POST",
                                "/api/v1/devices",
                                admin,
                                mapper.writeValueAsString(
                                        Map.of(
                                                "installationId",
                                                installation.get("id").asText(),
                                                "serialNumber",
                                                UUID.randomUUID().toString(),
                                                "deviceId",
                                                UUID.randomUUID(),
                                                "supplyPointId",
                                                point.get("id").asText(),
                                                "installerId",
                                                installer,
                                                "location",
                                                "Kitchen",
                                                "installedAt",
                                                Instant.now().minusSeconds(1).toString())),
                                correlation.toString()),
                        202);
        UUID deviceId = UUID.fromString(receipt.get("device").get("id").asText());
        assertEquals(correlation, store.listAudit("DEVICE", deviceId).getFirst().correlationId());
        String list = "/api/v1/operations/outbox?deviceId=" + deviceId;
        assertProblem(request("GET", list, owner, null, null), 403);
        JsonNode jobs = body(request("GET", list, admin, null, null), 200);
        assertEquals(1, jobs.size());
        assertEquals(correlation.toString(), jobs.get(0).get("correlationId").asText());
        assertFalse(jobs.toString().contains(receipt.get("oneTimeCredential").asText()));
        assertFalse(jobs.get(0).has("payload"));
        assertFalse(jobs.get(0).has("claimToken"));
        UUID retryCorrelation = UUID.randomUUID();
        String jobId = jobs.get(0).get("id").asText();
        body(
                request(
                        "POST",
                        "/api/v1/operations/outbox/" + jobId + "/retry",
                        admin,
                        "{\"reason\":\"Reviewed connection\"}",
                        retryCorrelation.toString()),
                200);
        assertEquals(
                retryCorrelation,
                store.listAudit("OUTBOX", UUID.fromString(jobId)).getFirst().correlationId());
    }

    private JsonNode paidFixture(JsonNode property, JsonNode point) {
        UUID id = UUID.randomUUID();
        var i =
                new com.ventrax.installation.shared.domain.InstallationModels.Installation(
                        id,
                        UUID.fromString(property.get("accountId").asText()),
                        UUID.fromString(property.get("id").asText()),
                        UUID.fromString(point.get("id").asText()),
                        null,
                        com.ventrax.installation.shared.domain.InstallationModels.InstallationStatus
                                .PENDING,
                        null,
                        Instant.now(),
                        null,
                        0);
        store.saveInstallation(i, -1);
        jdbc.update(
                "INSERT INTO"
                    + " installation_requests(request_id,contact_name,contact_email,contact_phone,exact_location,purchase_order_id,payment_verified_at)"
                    + " VALUES (?,'HTTP fixture','fixture@example.test','123','Fixture',?,now())",
                id,
                UUID.randomUUID());
        return mapper.valueToTree(i);
    }

    private JsonNode body(HttpResponse<String> response, int status) {
        assertEquals(status, response.statusCode(), response.body());
        return mapper.readTree(response.body());
    }

    private static String roleToken(UUID account, String role) {
        return Jwts.builder()
                .audience()
                .add("smartgn-api")
                .and()
                .subject(account.toString())
                .issuer(ISSUER)
                .issuedAt(Date.from(Instant.now().minusSeconds(30)))
                .expiration(Date.from(Instant.now().plusSeconds(300)))
                .claim("accountId", account.toString())
                .claim("role", role)
                .claim("plan", "PRO")
                .signWith(Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }
}
