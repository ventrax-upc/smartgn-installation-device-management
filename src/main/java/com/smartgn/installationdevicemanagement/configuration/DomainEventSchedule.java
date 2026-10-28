package com.smartgn.installationdevicemanagement.configuration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.*;

@Component
public class DomainEventSchedule {
    private final JdbcTemplate db;
    private final String url, token;
    private final RestClient http;

    public DomainEventSchedule(
            JdbcTemplate db,
            @Value("${smartgn.events.sink-url:}") String url,
            @Value("${smartgn.events.sink-token:}") String token) {
        this.db = db;
        this.url = url;
        this.token = token;
        var f =
                new org.springframework.http.client.JdkClientHttpRequestFactory(
                        java.net.http.HttpClient.newBuilder()
                                .connectTimeout(java.time.Duration.ofSeconds(2))
                                .build());
        f.setReadTimeout(java.time.Duration.ofSeconds(2));
        http = RestClient.builder().requestFactory(f).build();
    }

    @Scheduled(fixedDelay = 1000)
    public void send() {
        if (url.isBlank()) return;
        UUID claim = UUID.randomUUID();
        var jobs =
                db.queryForList(
                        "UPDATE domain_outbox SET claim_id=?,lease_until=now()+interval '15"
                            + " seconds' WHERE event_id=(SELECT event_id FROM domain_outbox WHERE"
                            + " published_at IS NULL AND available_at<=now() AND (lease_until IS"
                            + " NULL OR lease_until<now()) ORDER BY created_at LIMIT 1 FOR UPDATE"
                            + " SKIP LOCKED) RETURNING"
                            + " event_id,event_type,aggregate_id,payload,correlation_id",
                        claim);
        for (var j : jobs)
            try {
                http.post()
                        .uri(url)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", j.get("event_id").toString())
                        .header("X-Correlation-ID", j.get("correlation_id").toString())
                        .body(j)
                        .retrieve()
                        .toBodilessEntity();
                db.update(
                        "UPDATE domain_outbox SET published_at=now(),lease_until=NULL WHERE"
                                + " event_id=? AND claim_id=?",
                        j.get("event_id"),
                        claim);
            } catch (Exception unavailable) {
                db.update(
                        "UPDATE domain_outbox SET available_at=now()+interval '30"
                                + " seconds',lease_until=NULL WHERE event_id=? AND claim_id=?",
                        j.get("event_id"),
                        claim);
            }
    }
}
