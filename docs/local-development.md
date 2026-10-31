# Local execution

Run `./scripts/start-local.ps1 -Integrated` from any sibling repository to start API Gateway, IAM, Installation & Device Management, Support & Maintenance and Payments & Billing. The four domain services use independent PostgreSQL databases; the Gateway is stateless. The composition reads shared credentials from `../smartgn-payments-billing/.env` and Gateway settings from `../smartgn-api-gateway/.env`. Preparation creates missing values and preserves existing keys and volumes.

| Application | Host port | Docker service port |
|---|---:|---:|
| API Gateway | 8000 | 8000 |
| IAM | 18080 | 8080 |
| Installation & Device Management | 18081 | 8081 |
| Support & Maintenance | 8082 | 8082 |
| Payments & Billing | 28083 | 8083 |

Standalone execution uses `./scripts/start-local.ps1`, `compose.yml` and this service's `.env`. Dependency URLs and service credentials must match the separately running services. Existing PostgreSQL volumes retain their database credentials; generating a new environment value does not change a stored database password.

The integrated financial event broker listens on 1885. Device provisioning uses EMQX on MQTT 1886 and administration 18086. A labeled Telemetry fixture on 18090 supplies controlled device-contact and buffer evidence. A separate fixture on 18091 accepts activation/recovery messages. These fixtures are local adapters and do not implement the external provider or Telemetry bounded context.

Each Java service exposes `/actuator/health`, `/v3/api-docs` and `/swagger-ui/index.html`. The Gateway exposes process liveness at `/health`, four-backend readiness at `/ready`, and public-only Swagger UI at `/swagger-ui/index.html`. Individual service OpenAPI exports are available in `docs/openapi.json`.

Stop the integrated stack with `docker compose --env-file ../smartgn-payments-billing/.env -f compose.integration.yml stop`. Stopping containers retains database and photograph volumes.
