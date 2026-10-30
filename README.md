# SmartGN Installation and Device Management

Properties, supply points, accredited external installers, installation requests, device provisioning and association history.

Java 25, Spring Boot 4.1.1, PostgreSQL, Flyway and authenticated REST contracts. Source packages use `com.ventrax.installation`. Each bounded context owns its data; integration uses HTTP and durable events.

## Run locally

Requirements: Docker Desktop Linux engine, PowerShell 7 and the four sibling checkouts under the same parent directory. Python standard library is used by acceptance and OpenAPI helpers. A host JDK is optional.

```powershell
./scripts/start-local.ps1 -Integrated
./scripts/test.ps1
./scripts/smoke-local.ps1
```

The shared integrated composition uses `../smartgn-payments-billing/.env` as its local configuration authority. Preparation preserves existing secrets and application volumes. All service databases are independent. Never delete volumes to bypass a migration or authentication error.

| Service | Integrated address |
| --- | --- |
| IAM | http://localhost:18080/swagger-ui.html |
| Installation | http://localhost:18081/swagger-ui.html |
| Payments | http://localhost:28083/swagger-ui.html |
| Support | http://localhost:8082/swagger-ui.html |

`./scripts/start-local.ps1` starts this service's standalone composition using ignored `.env`; configure shared service credentials and external URLs before connecting separately started services. Its default HTTP port is 8081. `-SkipBuild` reuses application images. `/actuator/health` reports application/database health. External dependency failure is not a successful business operation.

## Repository layout

- `src/main/java/com/ventrax/installation` and mirrored tests contain the service implementation.
- `src/main/resources/db/migration` contains immutable versioned migrations.
- `docs` contains service architecture, contracts, local development, overview and exported OpenAPI.
- `scripts` contains preparation, startup, isolated tests, smoke and export commands.
- `tools` contains verification helpers, service metadata, health probe and explicit local fixtures.
- `.mvn`, `mvnw` and `mvnw.cmd` provide the shared Maven 3.9.11 wrapper; Docker uses JDK 25.

## Verification and configuration

`./scripts/test.ps1` runs Maven verify with disposable PostgreSQL and authenticated MQTT fixtures. `-SkipDatabase` is a reduced check and does not prove persistence or broker integration. `./mvnw.cmd verify` requires host JDK 25 and dedicated test database settings for persistence tests. Test resources are isolated from application volumes.

`./scripts/smoke-local.ps1` checks the four-service identity/ownership/ticket/visit/coverage workflow. Purchase provisioning and notification delivery use explicitly labeled local fixtures; merchant, tax and physical-device integrations retain their own readiness requirements.

Use `python tools/verify-layout.py` to check package paths and the shared repository structure. Use `./scripts/export-openapi.ps1` to refresh the export from a running service; set OPENAPI_URL to an integrated address when necessary. Never commit `.env`, `.local`, credentials or generated build output.

## Documentation

- [Architecture](docs/architecture.md)
- [API and integration contracts](docs/contracts.md)
- [Local development](docs/local-development.md)
- [Service overview](docs/service-overview.md)
- [OpenAPI](docs/openapi.json)
