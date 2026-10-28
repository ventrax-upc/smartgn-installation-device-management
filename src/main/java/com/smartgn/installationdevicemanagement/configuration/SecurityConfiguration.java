package com.smartgn.installationdevicemanagement.configuration;

import com.smartgn.installationdevicemanagement.shared.domain.Actor;
import com.smartgn.installationdevicemanagement.shared.domain.Role;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import javax.crypto.SecretKey;

@Configuration
public class SecurityConfiguration {
    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            @Value("${smartgn.security.jwt-secret}") String secret,
            @Value("${smartgn.security.jwt-issuer}") String issuer,
            @Value("${smartgn.security.jwt-audience}") String audience,
            @Value("${smartgn.security.allowed-origins}") String origins,
            com.smartgn.installationdevicemanagement.shared.application.port.out.IdentityGateway
                    identities,
            @Value("${smartgn.telemetry.service-token}") String internalToken)
            throws Exception {
        var cors = new CorsConfiguration();
        cors.setAllowedOrigins(
                Arrays.stream(origins.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList());
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Correlation-ID"));
        cors.setExposedHeaders(List.of("X-Correlation-ID", "Content-Disposition"));
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);
        return http.cors(c -> c.configurationSource(source))
                .csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(
                        a ->
                                a.requestMatchers(
                                                "/actuator/health",
                                                "/actuator/health/**",
                                                "/swagger-ui.html",
                                                "/swagger-ui/**",
                                                "/v3/api-docs",
                                                "/v3/api-docs/**")
                                        .permitAll()
                                        .requestMatchers(
                                                org.springframework.http.HttpMethod.POST,
                                                "/api/v1/installations")
                                        .permitAll()
                                        .requestMatchers("/api/v1/**", "/internal/v1/**")
                                        .authenticated()
                                        .anyRequest()
                                        .denyAll())
                .exceptionHandling(
                        e ->
                                e.authenticationEntryPoint(
                                                (req, res, ex) ->
                                                        writeError(
                                                                res,
                                                                401,
                                                                "UNAUTHENTICATED",
                                                                "A valid IAM bearer token is"
                                                                        + " required"))
                                        .accessDeniedHandler(
                                                (req, res, ex) ->
                                                        writeError(
                                                                res,
                                                                403,
                                                                "FORBIDDEN",
                                                                "Access is not allowed")))
                .addFilterBefore(
                        new IamTokenFilter(secret, issuer, audience, identities, internalToken),
                        UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    static void writeError(HttpServletResponse response, int status, String code, String detail)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.getWriter()
                .write(
                        "{\"type\":\"urn:smartgn:error:"
                                + code.toLowerCase()
                                + "\",\"title\":\""
                                + code
                                + "\",\"status\":"
                                + status
                                + ",\"detail\":\""
                                + detail
                                + "\",\"code\":\""
                                + code
                                + "\"}");
    }

    static final class IamTokenFilter extends OncePerRequestFilter {
        private final SecretKey key;
        private final String issuer;
        private final String audience;
        private final com.smartgn.installationdevicemanagement.shared.application.port.out
                        .IdentityGateway
                identities;
        private final String internalToken;

        IamTokenFilter(String secret, String issuer, String audience) {
            this(secret, issuer, audience, null, "");
        }

        IamTokenFilter(
                String secret,
                String issuer,
                String audience,
                com.smartgn.installationdevicemanagement.shared.application.port.out.IdentityGateway
                        identities,
                String internalToken) {
            this.identities = identities;
            this.internalToken = internalToken;
            if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
                throw new IllegalArgumentException(
                        "JWT_SECRET must contain at least 32 UTF-8 bytes, matching IAM");
            }
            key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
            this.issuer = issuer;
            this.audience = audience.isBlank() ? "smartgn-api" : audience;
        }

        @Override
        protected void doFilterInternal(
                HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            String header = request.getHeader(HttpHeaders.AUTHORIZATION);
            if (request.getRequestURI().startsWith("/internal/")) {
                if (internalToken.length() < 32
                        || header == null
                        || !java.security.MessageDigest.isEqual(
                                ("Bearer " + internalToken).getBytes(StandardCharsets.UTF_8),
                                header.getBytes(StandardCharsets.UTF_8))) {
                    writeError(
                            response, 401, "UNAUTHENTICATED", "A service credential is required");
                    return;
                }
                SecurityContextHolder.getContext()
                        .setAuthentication(
                                new UsernamePasswordAuthenticationToken(
                                        "service",
                                        null,
                                        List.of(new SimpleGrantedAuthority("SERVICE"))));
                chain.doFilter(request, response);
                return;
            }
            if (header != null && header.startsWith("Bearer ")) {
                try {
                    Claims claims =
                            Jwts.parser()
                                    .verifyWith(key)
                                    .requireIssuer(issuer)
                                    .build()
                                    .parseSignedClaims(header.substring(7))
                                    .getPayload();
                    if (claims.getExpiration() == null
                            || claims.getIssuedAt() == null
                            || claims.getIssuedAt()
                                    .toInstant()
                                    .isAfter(Instant.now().plusSeconds(60))) {
                        throw new IllegalArgumentException("Missing or invalid token timestamps");
                    }
                    UUID account = UUID.fromString(claims.get("accountId", String.class));
                    if (!account.toString().equals(claims.getSubject()))
                        throw new IllegalArgumentException("Account mismatch");
                    if (!audience.isBlank()
                            && (claims.getAudience() == null
                                    || !claims.getAudience().contains(audience))) {
                        throw new IllegalArgumentException("Audience mismatch");
                    }
                    Actor actor;
                    if (identities != null) {
                        var live = identities.resolve(header.substring(7));
                        if (!account.equals(live.accountId())
                                || !"ACTIVE".equals(live.status())
                                || !live.role().name().equals(claims.get("role", String.class)))
                            throw new IllegalArgumentException("Inactive account");
                        actor =
                                new Actor(
                                        account,
                                        live.role(),
                                        live.accountKind(),
                                        live.principalAccountId());
                    } else
                        actor = new Actor(account, Role.valueOf(claims.get("role", String.class)));
                    var authentication =
                            new UsernamePasswordAuthenticationToken(
                                    actor,
                                    null,
                                    List.of(
                                            new SimpleGrantedAuthority(
                                                    "ROLE_" + actor.role().name())));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                    request.setAttribute("actor", actor);
                } catch (
                        com.smartgn.installationdevicemanagement.shared.domain.DomainException
                                dependency) {
                    writeError(
                            response,
                            "UNAUTHENTICATED".equals(dependency.code()) ? 401 : 503,
                            dependency.code(),
                            "Current IAM authorization could not be confirmed");
                    return;
                } catch (RuntimeException invalidToken) {
                    SecurityContextHolder.clearContext();
                }
            }
            chain.doFilter(request, response);
        }
    }
}
