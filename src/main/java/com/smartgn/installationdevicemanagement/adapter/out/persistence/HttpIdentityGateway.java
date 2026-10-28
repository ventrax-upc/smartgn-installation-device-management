package com.smartgn.installationdevicemanagement.adapter.out.persistence;

import com.smartgn.installationdevicemanagement.shared.application.port.out.IdentityGateway;
import com.smartgn.installationdevicemanagement.shared.domain.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.*;

@Component
public class HttpIdentityGateway implements IdentityGateway {
    private final RestClient http;
    private final String token;

    public HttpIdentityGateway(
            @Value("${smartgn.iam.base-url:http://iam:8080}") String url,
            @Value("${smartgn.telemetry.service-token}") String token) {
        this.token = token;
        var f =
                new org.springframework.http.client.JdkClientHttpRequestFactory(
                        java.net.http.HttpClient.newBuilder()
                                .connectTimeout(Duration.ofSeconds(2))
                                .build());
        f.setReadTimeout(Duration.ofSeconds(2));
        http =
                RestClient.builder()
                        .baseUrl(url)
                        .requestFactory(f)
                        .requestInterceptor(
                                new com.smartgn.installationdevicemanagement.integration
                                        .TraceHttpRequests())
                        .defaultHeader("Authorization", "Bearer " + token)
                        .build();
    }

    private Account map(Map m) {
        return new Account(
                UUID.fromString(m.get("accountId").toString()),
                m.get("email").toString(),
                Role.valueOf(m.get("role").toString()),
                m.get("accountKind").toString(),
                m.get("status").toString(),
                m.get("principalAccountId") == null
                        ? null
                        : UUID.fromString(m.get("principalAccountId").toString()));
    }

    public Account resolve(String bearer) {
        try {
            return map(
                    http.post()
                            .uri("/internal/v1/sessions/introspect")
                            .body(Map.of("token", bearer))
                            .retrieve()
                            .body(Map.class));
        } catch (org.springframework.web.client.HttpClientErrorException.Unauthorized denied) {
            throw new DomainException("UNAUTHENTICATED", "Session is no longer valid");
        } catch (Exception ex) {
            throw new DomainException("DEPENDENCY_UNAVAILABLE", "IAM session state is unavailable");
        }
    }

    public Account account(UUID id) {
        try {
            return map(http.get().uri("/internal/v1/accounts/{id}", id).retrieve().body(Map.class));
        } catch (Exception ex) {
            throw new DomainException("DEPENDENCY_UNAVAILABLE", "IAM account state is unavailable");
        }
    }

    public boolean authorize(UUID account, UUID meter, String function) {
        try {
            var m =
                    http.post()
                            .uri("/internal/v1/authorizations/check")
                            .body(
                                    Map.of(
                                            "accountId",
                                            account,
                                            "meterId",
                                            meter,
                                            "function",
                                            function,
                                            "requiresPro",
                                            false))
                            .retrieve()
                            .body(Map.class);
            return m != null && Boolean.TRUE.equals(m.get("allowed"));
        } catch (Exception ex) {
            throw new DomainException(
                    "DEPENDENCY_UNAVAILABLE", "IAM permission state is unavailable");
        }
    }

    public Map<UUID, Set<String>> meters(UUID account) {
        try {
            var m =
                    http.get()
                            .uri("/internal/v1/accounts/{id}/grants", account)
                            .retrieve()
                            .body(Map.class);
            Map<UUID, Set<String>> result = new HashMap<>();
            if (m != null)
                m.forEach(
                        (k, v) ->
                                result.put(
                                        UUID.fromString(k.toString()),
                                        new HashSet<>((List<String>) v)));
            return result;
        } catch (Exception ex) {
            throw new DomainException("DEPENDENCY_UNAVAILABLE", "IAM grants are unavailable");
        }
    }
}
