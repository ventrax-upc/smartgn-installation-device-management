# Local development

Run `scripts/start-local.ps1` from this checkout. It uses `compose.integration.yml`, preserves existing local configuration and volumes, and requires an adjacent IAM checkout (or `IAM_REPO_PATH`). Do not run the standalone IAM composition simultaneously on the same published ports.

`scripts/prepare-local.ps1` generates random secrets once in ignored `.local/integration.env` and writes the ignored EMQX bootstrap key file. Keep that configuration with its persistent volumes: changing encryption keys does not decrypt older queued credentials or messages. No script resets stored account passwords.

The two PostgreSQL servers have different credentials, volumes and service DNS names. IAM uses PostgreSQL 18 and Installation PostgreSQL 17.6. The default `compose.yml` includes the same integration composition; explicit commands should pass `--env-file .local/integration.env`.

```powershell
docker compose --env-file .local/integration.env -f compose.integration.yml ps
docker compose --env-file .local/integration.env -f compose.integration.yml up -d
./scripts/test.ps1
./scripts/smoke-local.ps1
```

Test runners create their own temporary database containers and clean up only those containers. Installation integration tests use uniquely generated schemas; IAM persistence tests likewise use generated schemas in their private verification database. Existing application databases are not used by test runners.

## Database operation

Flyway initializes an empty database at startup. A single initial SQL schema defines the database at startup. Keep persistent Docker volumes and encryption keys together. Installer eligibility requires an accreditation code, enabled status and explicit availability. Active assignment reservations enforce installer and request uniqueness.

Installation uses the `installation` PostgreSQL schema. This is a storage identifier; the application name and Java namespace are Installation & Device Management. Database access is private to each service.

JWTs require USER or SUPERADMIN, the configured issuer and audience, an active account and the current session version. Clients use POST assignment/completion and the device payload documented in OpenAPI.

Local services communicate over HTTP/MQTT inside an isolated Docker network with loopback host bindings. Production deployment requires separate transport security and external provider configuration.
