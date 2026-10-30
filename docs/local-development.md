# Local development

Run `./scripts/start-local.ps1 -Integrated` from any sibling service. The identical integrated composition starts IAM, Installation, Payments and Support, each with its independent database. Configuration comes from `../smartgn-payments-billing/.env`; preparation generates only missing keys and preserves existing volumes. Integrated ports are IAM 18080, Installation 18081, Payments 28083 and Support 8082. The broker is on 1885; activation/recovery delivery uses a protected local fixture on 18091.

Standalone mode uses `compose.yml` and the service's ignored `.env`. Match JWT/service credentials and configure dependency URLs when connecting standalone deployments. Changes to an existing database password require a deliberate database credential update; generating a new environment value does not update stored PostgreSQL credentials.

Use `scripts/test.ps1` for isolated verification, `scripts/smoke-local.ps1` for integrated acceptance, and `tools/verify-layout.py` for the common repository contract. Logs and Maven output remain outside docs. OpenAPI exports describe the running implementation; set OPENAPI_URL when exporting from a non-default port.

Applied Flyway migrations are immutable. Back up an existing database before introducing migrations and validate the schema before any explicit baseline. Do not reset application volumes to make a migration pass. Fixtures are development tools, not productive integrations.

## Provisioning fixtures

`compose.fixtures.yml` retains the independent IAM/Installation/EMQX/Telemetry development composition. Existing `.local/integration.env` belongs only to that fixture mode. `scripts/start-fixtures.ps1` starts it and `python tools/smoke-service.py` exercises physical-device contract simulations. It is separate from the four-service integrated stack and never implies real device or Telemetry availability.

After the integrated acceptance run, `python tools/verify-mqtt.py` replays committed Support completion events through authenticated MQTT, checks that no duplicate coverage or monetary conflicts appear, and restores the default HTTP-only Support configuration. It retains all application volumes and existing delivery receipts.

Set `OPENAPI_URL` to the running service's `/v3/api-docs` URL and run `python tools/verify-openapi.py` to verify the API title, version, operation inventory, groups, summaries, descriptions, authentication and success responses against `src/main/resources/openapi/operations.json`. Use `python tools/export-openapi.py` with the same variable to refresh `docs/openapi.json`.
