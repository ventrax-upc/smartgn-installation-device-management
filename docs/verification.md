# Executed local verification

Verified on 2026-10-28 with Docker Desktop's Linux engine. Applications and Maven verification ran in Java 25 containers, with Spring Boot 3.5.6 (IAM) and 4.1.1 (Installation), PostgreSQL 18 and 17.6 respectively, and real EMQX 5.8.8.

Commands below run from the `smartgn-installation-device-management` checkout, except the explicitly invoked IAM script.

## Complete automated suites

- `./scripts/test.ps1`: BUILD SUCCESS, **64 tests, 0 failures, 0 errors, 0 skipped**. Includes unit, ArchUnit, Cucumber, HTTP and PostgreSQL checks. The six Cucumber scenarios are included in this result, not additional test methods.
- `pwsh -NoProfile -File ../smartgn-iam/scripts/test.ps1`: BUILD SUCCESS, **45 tests, 0 failures, 0 errors, 0 skipped**.
- Both scripts use fresh, uniquely named verification containers; database tests isolate generated schemas. Application database volumes are not reset or removed.
- `docker compose --env-file .local/integration.env -f compose.integration.yml build`: both application images built successfully. The same images passed the acceptance run below.

Coverage includes schema initialization and database integrity, canonical roles, pending accounts, expired/reused/wrong-purpose tokens, concurrent activation and recovery, current password verification and session invalidation, tenant isolation, denied permissions, stale versions, paid installer eligibility and competing reservations, duplicate physical devices, transactional rollback, provisioning failures/retries, outbox leasing, association history, credential encryption and rotation. Closure checks include 100 PostgreSQL concurrent pairs and ten concurrent HTTP completion calls with a stable completion date and one completion event.

## Joint Docker acceptance

`./scripts/smoke-local.ps1` runs inside the Docker network against the actual IAM and Installation applications, their separate PostgreSQL databases and actual EMQX. The recorded run passed **235 HTTP checks**, including retry polling. This is a request count, not 230 independent test cases.

The run covered purchase replay/conflicts, unavailable notification delivery followed by recovery, activation and login, profile updates, no initial delegated grants, independent functions on meters A/B, rejection of foreign-meter and financial grants, revocation with an existing JWT, principal-only administration, positive/negative Pro decisions, paid automatic assignment/reassignment, provisioning, publication on an allowed MQTT topic, denial on another topic, concurrent completion, rotation, and live MQTT revocation. Password change and recovery invalidated earlier JWTs; API boundary checks passed.

Measured broker-confirmed revocation: **2.149 seconds**, below the 30-second target in this local run. The existing MQTT connection was disconnected and revoked credentials could not reconnect. This single local measurement is not a load or production latency guarantee.

The notification outage test restored its fixture in a finally block. Durable queued deliveries resumed without logging tokens; accepted fixture submissions are not claims of email delivery. General events were checked for duplicate completion and secret leakage.

## OpenAPI and repository checks

`tools/export-openapi.py` fetched the running schemas and checked required operations, public/service security declarations, context boundaries and hidden framework parameters: **16 IAM paths and 27 Installation paths**. Snapshots are versioned in the respective repositories. `git diff --check` passed in both repositories.

Flyway initializes each PostgreSQL database from a single schema definition. Persistence tests cover identity records and assignment records in isolated schemas. The Installation service uses the `installation` storage schema. Application data remains in persistent Docker volumes.

## Explicit integration limits

Billing, Notifications/event delivery and Telemetry confirmations/operational metadata use labeled local contract doubles. Pro decisions and dependency outages were simulated through those adapters. Actual Telemetry ingestion, physical hardware, payment-provider verification, external email delivery, production TLS and sustained-load behavior were not verified. Their future provider contracts remain replaceable; these fixtures are not definitive external contracts. EMQX authentication, topic authorization, rotation and revocation were exercised against the real broker.

## Language and documentation conventions

Source identifiers, comments, application messages, API examples, tests and service documentation use English. The repository-wide language audit covered 200 tracked text files, including source, build/configuration files, contracts, scripts, tests and documentation. Database schemas, SQL identifiers and bootstrap fixtures also use English.

Documentation describes service responsibilities, current contracts, operational rules and executed verification. The service overview is in `service-overview.md`.
