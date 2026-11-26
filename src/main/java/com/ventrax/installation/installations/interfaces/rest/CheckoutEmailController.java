package com.ventrax.installation.installations.interfaces.rest;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@RestController
@io.swagger.v3.oas.annotations.tags.Tag(name = "Installation requests")
@io.swagger.v3.oas.annotations.security.SecurityRequirements
@RequestMapping("/api/v1/installations/email-verifications")
public class CheckoutEmailController {
    public record Send(@NotBlank @Email @Size(max = 254) String email) {}
    public record Confirm(@NotNull UUID challengeId, @NotBlank @Pattern(regexp = "[0-9]{6}") String code) {}
    public record Challenge(UUID challengeId) {}
    public record Proof(UUID challengeId, String proof) {}

    private final CheckoutEmail verification;

    public CheckoutEmailController(CheckoutEmail verification) {
        this.verification = verification;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Challenge send(@Valid @RequestBody Send request) {
        return new Challenge(verification.send(request.email()));
    }

    @PostMapping("/confirm")
    public Proof confirm(@Valid @RequestBody Confirm request) {
        String proof = verification.confirm(request.challengeId(), request.code());
        if (proof == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid or expired verification code");
        }
        return new Proof(request.challengeId(), proof);
    }
}

@Service
class CheckoutEmail {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final String mode;
    private final String fixtureUrl;
    private final String serviceToken;
    private final String resendKey;
    private final String from;
    private final SecureRandom random = new SecureRandom();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    CheckoutEmail(
            JdbcTemplate db,
            ObjectMapper json,
            @Value("${smartgn.checkout-email.mode}") String mode,
            @Value("${smartgn.checkout-email.fixture-url}") String fixtureUrl,
            @Value("${smartgn.telemetry.service-token}") String serviceToken,
            @Value("${smartgn.checkout-email.resend-key}") String resendKey,
            @Value("${smartgn.checkout-email.from}") String from) {
        this.db = db;
        this.json = json;
        this.mode = mode;
        this.fixtureUrl = fixtureUrl;
        this.serviceToken = serviceToken;
        this.resendKey = resendKey;
        this.from = from;
    }

    UUID send(String address) {
        boolean local = mode.equals("local") && !fixtureUrl.isBlank();
        boolean resend = mode.equals("resend") && !resendKey.isBlank() && !from.isBlank();
        if (!local && !resend) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        String email = normalize(address);
        UUID challenge = UUID.randomUUID();
        String code = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
        db.update("DELETE FROM checkout_email_verifications WHERE expires_at < now() - interval '1 day'");
        int written = db.update("""
                INSERT INTO checkout_email_verifications(email,challenge,code_hash,expires_at)
                VALUES (?,?,?,now() + interval '10 minutes')
                ON CONFLICT (email) DO UPDATE SET challenge=excluded.challenge, code_hash=excluded.code_hash,
                    expires_at=excluded.expires_at, attempts=0, proof_hash=NULL, installation_id=NULL
                WHERE checkout_email_verifications.expires_at < now() + interval '9 minutes'
                """, email, challenge, digest(challenge, code));
        if (written == 0) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
        Map<String, ?> body = local
                ? Map.of("email", email, "purpose", "CHECKOUT_EMAIL", "code", code, "challengeId", challenge)
                : Map.of("to", List.of(email), "from", from, "subject", "SmartGN: código de verificación",
                        "text", "Tu código de SmartGN es " + code + ". Vence en 10 minutos.");
        try {
            var request = HttpRequest.newBuilder(URI.create(local ? fixtureUrl : "https://api.resend.com/emails"))
                    .header("Authorization", "Bearer " + (local ? serviceToken : resendKey))
                    .header("Content-Type", "application/json")
                    .header("Idempotency-Key", "smartgn-checkout-" + challenge)
                    .timeout(Duration.ofSeconds(8))
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                    .build();
            int status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status >= 200 && status < 300) return challenge;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (java.io.IOException ignored) {
            // The caller can request another code after the cooldown.
        }
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Email delivery unavailable");
    }

    boolean requiresVerification() {
        return mode.equals("resend");
    }

    @Transactional
    public String confirm(UUID challenge, String code) {
        var rows = db.queryForList("""
                SELECT code_hash FROM checkout_email_verifications
                WHERE challenge=? AND expires_at>now() AND attempts<5 AND proof_hash IS NULL FOR UPDATE
                """, challenge);
        if (rows.isEmpty()) return null;
        String expected = (String) rows.getFirst().get("code_hash");
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                digest(challenge, code).getBytes(StandardCharsets.UTF_8))) {
            db.update("UPDATE checkout_email_verifications SET attempts=attempts+1 WHERE challenge=?", challenge);
            return null;
        }
        String proof = UUID.randomUUID().toString() + UUID.randomUUID();
        db.update("""
                UPDATE checkout_email_verifications SET proof_hash=?, expires_at=now() + interval '15 minutes'
                WHERE challenge=?
                """, digest(challenge, proof), challenge);
        return proof;
    }

    UUID installation(UUID challenge, String proof, String email, java.util.function.Supplier<UUID> create) {
        var rows = db.queryForList("""
                SELECT installation_id FROM checkout_email_verifications
                WHERE challenge=? AND email=? AND proof_hash=? AND expires_at>now() FOR UPDATE
                """, challenge, normalize(email), digest(challenge, proof));
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Email verification required");
        UUID existing = (UUID) rows.getFirst().get("installation_id");
        if (existing != null) return existing;
        UUID id = create.get();
        db.update("UPDATE checkout_email_verifications SET installation_id=? WHERE challenge=?", id, challenge);
        return id;
    }

    private static String normalize(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }

    private String digest(UUID challenge, String value) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(serviceToken.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((challenge + ":" + value).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("Checkout verification is unavailable", exception);
        }
    }
}
