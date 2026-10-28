# SmartGN Installation & Device Management

Java 25 / Spring Boot 4.1.1 service for properties, supply points, accredited external IoT installers, provisional and paid installation requests, device associations and provisioning. IAM owns identities and delegated permissions. Support, consumption reports, tariffs, subscription decisions and raw telemetry are outside this service.

## Run locally with Docker

Prerequisites: Docker Desktop with its Linux engine running, PowerShell 7, and the IAM checkout at `../smartgn-iam`. Set `IAM_REPO_PATH` when using another checkout location. No host JDK or Maven installation is required.

```powershell
./scripts/start-local.ps1
./scripts/test.ps1
pwsh -NoProfile -File ../smartgn-iam/scripts/test.ps1
./scripts/smoke-local.ps1
```

The startup script generates ignored `.local/integration.env`, builds both services and starts `compose.integration.yml`. It never deletes volumes, recreates databases, resets existing passwords or changes an existing account's role. `-DependenciesOnly` starts the databases, EMQX and explicitly labeled future-service doubles; `-SkipBuild` reuses built application images.

| Local endpoint | Purpose |
| --- | --- |
| `http://localhost:8080/swagger-ui.html` | IAM OpenAPI |
| `http://localhost:8081/swagger-ui.html` | Installation OpenAPI |
| `localhost:5432` / `localhost:5433` | Independent IAM PostgreSQL 18 / Installation PostgreSQL 17.6 |
| `localhost:1883` / `localhost:18083` | Local EMQX MQTT / administration |
| `localhost:8090` / `localhost:8091` | Telemetry / future-service contract doubles |

All published ports bind to loopback. Container-to-container calls use Docker DNS. The opt-in `local-bootstrap` profile creates the generated local SuperAdmin only when its email is absent; credentials are in `.local/integration.env` and are never printed by scripts. Installer catalogue records never create IAM accounts.

This composition provides labeled Billing/Notifications doubles and a provisional Telemetry adapter. To connect actual Payments & Billing, use its integrated composition or configure the authenticated checkout, payment-confirmation and event-delivery contracts described in the API documentation.

## Contracts and ownership

- [Architecture](docs/architecture.md)
- [Local development](docs/local-development.md)
- [Public and private API contracts](docs/api/operational-workflows.md)
- [Device provisioning and provisional Telemetry port](docs/contracts/device-provisioning.md)
- [Exported OpenAPI](docs/api/openapi.json)

Code lives under `src/main/java/com/smartgn/installationdevicemanagement/`. Domain/application code uses immutable models and persistence/transaction ports; HTTP and PostgreSQL adapters translate those contracts. Flyway manages the `installation` PostgreSQL schema. Spring's `management.*` configuration namespace belongs to Actuator.

Support, maintenance, tariffs and reports belong to their respective bounded contexts. Each service owns its database and communicates through contracts and events.

See the [service overview](docs/service-overview.md) for a concise description of responsibilities.
