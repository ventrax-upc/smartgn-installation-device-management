# Local execution

Run `./scripts/start-local.ps1 -Integrated` to start all 11 backend services through the Gateway's integrated Compose. The stack uses one PostgreSQL instance with separate databases and roles, one EMQX broker, real Telemetry backed by InfluxDB, and Redis. The activation delivery and payment providers use local fixtures.

The Gateway publishes HTTP on `127.0.0.1:8000`. Spring Boot Admin is available at `127.0.0.1:8089`. See the [integrated port table](../../smartgn-api-gateway/docs/integrated-local.md#access) for the individual Swagger UIs. Backend calls use service DNS inside Compose. Shared local credentials are generated under the Gateway repository's ignored `.local/integrated.env`.

From the sibling `smartgn-api-gateway` repository, run `./scripts/seed-local.ps1` to load demo data and `./scripts/smoke-integrated.ps1` to check the integrated stack. The Gateway exposes `/health`, `/ready`, and public Swagger at `/swagger-ui/index.html`. Each Spring service exposes `/actuator/health` and `/v3/api-docs` on its service port. Its OpenAPI export is in `docs/openapi.json`.

For standalone execution, run `./scripts/start-local.ps1` with this service's `.env`. Set the dependency URLs and credentials for the services it will contact.

Run this command from the Gateway repository to stop the integrated stack while preserving volumes.

```powershell
docker compose --env-file .local/integrated.env -f compose.integration.yml down
```
