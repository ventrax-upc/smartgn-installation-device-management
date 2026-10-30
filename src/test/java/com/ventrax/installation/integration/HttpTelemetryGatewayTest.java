package com.ventrax.installation.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

class HttpTelemetryGatewayTest {
    interface Check {
        void run(HttpTelemetryGateway gateway) throws Exception;
    }

    void response(String path, String body, Check check) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                path,
                x -> {
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    x.getRequestBody().readAllBytes();
                    x.getResponseHeaders().set("Content-Type", "application/json");
                    x.sendResponseHeaders(200, bytes.length);
                    x.getResponseBody().write(bytes);
                    x.close();
                });
        server.start();
        try {
            check.run(
                    new HttpTelemetryGateway(
                            "http://127.0.0.1:" + server.getAddress().getPort(),
                            "test-service-token",
                            tools.jackson.databind.json.JsonMapper.builder()
                                    .findAndAddModules()
                                    .build()));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void associationAcknowledgementMustIdentifyTheExactDeviceAndVersion() throws Exception {
        UUID device = UUID.randomUUID();
        var a =
                new TelemetryGateway.Association(
                        device,
                        2,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        Instant.now(),
                        null);
        response(
                "/internal/v1/device-associations",
                "{\"deviceId\":\"" + device + "\",\"version\":1,\"confirmed\":true}",
                g -> assertThrows(DependencyFailure.class, () -> g.synchronize(a)));
        response(
                "/internal/v1/device-associations",
                "{\"deviceId\":\"" + device + "\",\"version\":2,\"confirmed\":true}",
                g -> assertDoesNotThrow(() -> g.synchronize(a)));
    }

    @Test
    void operationalMetadataCannotIntroduceAForeignDevice() throws Exception {
        UUID requested = UUID.randomUUID();
        response(
                "/internal/v1/devices/status-query",
                "[{\"deviceId\":\"" + UUID.randomUUID() + "\",\"connectionState\":\"CONNECTED\"}]",
                g ->
                        assertThrows(
                                DependencyFailure.class,
                                () -> g.operationalStatus(UUID.randomUUID(), List.of(requested))));
    }

    @Test
    void drainedBufferNeedsExplicitEvidenceAndZeroPendingReadings() throws Exception {
        UUID device = UUID.randomUUID();
        for (String body :
                List.of(
                        "{\"drained\":true,\"pendingReadings\":1,\"evidenceId\":\"proof\"}",
                        "{\"drained\":true,\"pendingReadings\":0,\"evidenceId\":\"\"}"))
            response(
                    "/internal/v1/devices/" + device + "/buffer-drain",
                    body,
                    g -> assertThrows(DependencyFailure.class, () -> g.drain(device, 1)));
    }

    @Test
    void nullMetadataEntriesFailAsAnUnavailableDependency() throws Exception {
        response(
                "/internal/v1/devices/status-query",
                "[null]",
                g ->
                        assertThrows(
                                DependencyFailure.class,
                                () ->
                                        g.operationalStatus(
                                                UUID.randomUUID(), List.of(UUID.randomUUID()))));
    }

    @Test
    void associationCallsPropagateTheCorrelationHeader() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String correlation = UUID.randomUUID().toString();
        UUID device = UUID.randomUUID();
        var seen = new java.util.concurrent.atomic.AtomicReference<String>();
        server.createContext(
                "/internal/v1/device-associations",
                x -> {
                    seen.set(x.getRequestHeaders().getFirst("X-Correlation-ID"));
                    byte[] bytes =
                            ("{\"deviceId\":\"" + device + "\",\"version\":1,\"confirmed\":true}")
                                    .getBytes(StandardCharsets.UTF_8);
                    x.getRequestBody().readAllBytes();
                    x.getResponseHeaders().set("Content-Type", "application/json");
                    x.sendResponseHeaders(200, bytes.length);
                    x.getResponseBody().write(bytes);
                    x.close();
                });
        server.start();
        org.slf4j.MDC.put("correlationId", correlation);
        try {
            new HttpTelemetryGateway(
                            "http://127.0.0.1:" + server.getAddress().getPort(),
                            "test-service-token",
                            tools.jackson.databind.json.JsonMapper.builder()
                                    .findAndAddModules()
                                    .build())
                    .synchronize(
                            new TelemetryGateway.Association(
                                    device,
                                    1,
                                    UUID.randomUUID(),
                                    UUID.randomUUID(),
                                    UUID.randomUUID(),
                                    Instant.now(),
                                    null));
            assertEquals(correlation, seen.get());
        } finally {
            org.slf4j.MDC.remove("correlationId");
            server.stop(0);
        }
    }
}
