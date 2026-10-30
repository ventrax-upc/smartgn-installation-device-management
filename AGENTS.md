# Repository guidelines

Java 25 and Spring Boot 4.1.1. The service base package is `com.ventrax.installation`. Preserve the bounded context and its independent PostgreSQL database. Domain/application logic uses explicit ports and framework adapters; retain existing JPA mappings in Support.

Source and tests live in `src/main` and `src/test`. Versioned Flyway migrations live in `src/main/resources/db/migration`; never edit applied migrations. Service documentation belongs in `docs`, local commands in `scripts`, and verification/fixture helpers in `tools`. Keep docs limited to implemented service behavior.

Run `./scripts/test.ps1` for isolated Docker verification with JDK 25 and PostgreSQL. `./scripts/start-local.ps1 -Integrated` starts the four-service local composition. Run `./scripts/smoke-local.ps1` after startup. Maven Wrapper supports host JDK 25. Verify `git diff --check` before committing.

Use four-space Java indentation, constructor injection, explicit domain errors and English API documentation. Preserve REST paths, external IDs, state history and USER/SUPERADMIN roles. External technicians/installers never receive IAM roles. Test ownership, function permissions, duplicate events, concurrency and dependency outages.

Never commit secrets, `.env`, `.local`, generated build output or device credentials. Do not reset application volumes. Keep test accounts isolated and label development fixtures. Do not claim productive provider integration from local fixtures. Commit only requested work and never push without user authorization.
