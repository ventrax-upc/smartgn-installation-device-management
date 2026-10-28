# Installation API contracts

The runtime OpenAPI and exported `openapi.json` define field schemas, validation and response codes. Protected resources require a current IAM JWT; global roles are USER and SUPERADMIN. Ownership and per-meter function checks remain in the backend.

## Public resources

Properties use POST/GET `/api/v1/properties`, GET/PUT/DELETE `/properties/{id}`, POST/GET `/properties/{propertyId}/supply-points` and GET/PUT `/supply-points/{id}`. Writes require principal ownership, immutable ownership and current versions. Deactivation is logical and preserves history. Delegated USERs do not acquire property administration rights. GET `/api/v1/meters` exposes only the current user's owned or delegated meters/functions.

Installer POST/GET `/api/v1/installers`, GET/PUT `/{id}` and PATCH `/{id}/status` require SuperAdmin. Payloads include firstName, lastName, identification, phone, optional email, accreditationCode and available. Updates/status changes use versions and audit. Catalogue entries never create IAM access.

| Method | Resource | Behavior |
| --- | --- | --- |
| POST | `/api/v1/installations` | `{propertyId?,supplyPointId?,contact:{name,email,phone},address}` creates PROVISIONAL. Anonymous requests cannot reference private targets; input/rate limits apply. No IAM account is created here. |
| GET | `/api/v1/installations/{id}` | Principal owner or SuperAdmin; installation plus private contact/appointment metadata and assigned installer contact |
| PUT | `/api/v1/installations/{id}/target` | `{propertyId,supplyPointId,version}` binds a paid PENDING request to the same principal's active point |
| POST | `/api/v1/installations/{id}/assign` | SuperAdmin override; verified purchase, linked target and eligible/free installer required |
| PATCH | `/api/v1/installations/{id}/reassign` | SuperAdmin, current version/reason, before work starts; preserves assignment history |
| PUT | `/api/v1/installations/{id}/appointment` | SuperAdmin, current version, future valid from/to window |
| PATCH | `/api/v1/installations/{id}/status` | SuperAdmin starts assigned work; accepts confirmed completion |
| POST | `/api/v1/installations/{id}/complete` | SuperAdmin; exact device/association, confirmed provisioning and fresh connectivity; duplicate closure preserves date/version |
| POST | `/api/v1/installations/{id}/cancel` | SuperAdmin, current version/reason; devices must have confirmed revocation first |
| POST | `/api/v1/devices` | SuperAdmin; `{deviceId,installationId,supplyPointId,installerId,serialNumber,location,installedAt,replacementGapReason?}`. UUID device identity and unique physical serial are distinct. Returns 202 and one-time credential with no-store. |
| GET | `/api/v1/devices` | SuperAdmin listing or principal point-scoped lookup; delegates must specify pointId/function and pass current IAM authorization |
| POST | `/api/v1/devices/{id}/credentials/rotate` or `/revoke` | Durable, asynchronous broker operations; pending confirmation is not success |
| POST | `/api/v1/devices/{id}/associations` | Audited versioned reassociation; retains association history and validates buffer drain/gap handling |

Automatic assignment uses deterministic UUID ordering and row locks plus unique active-assignment constraints. A busy, disabled, unavailable or unaccredited installer is not eligible. Completed/cancelled requests release their active reservation. Reassignment does not rewrite older assignment rows.

### Completion connectivity rule (US36)

A first completion requires the exact active device association and confirmed provisioning. Telemetry must report `CONNECTED`, with `lastContact` less than 30 seconds old and no more than 5 seconds in the future. Missing, stale or unavailable metadata prevents completion. The device version is checked again under the transaction lock. Repeating an already completed request preserves its completion date and does not require a new connectivity check.

## Private resources owned by Installation

Service authentication is independent of user JWTs.

- POST `/internal/v1/installations/payment-confirmations`: `{eventId,purchaseOrderId,installationId,customerAccountId,confirmed}` applies a trusted PaymentConfirmed input. The customer must match contact and existing ownership; receipts/events are deduplicated and conflicting payloads are rejected.
- GET `/internal/v1/meters/{id}/ownership`: returns the owner and active scope using local data only, avoiding an IAM callback loop.
- GET `/internal/v1/devices/{id}/authorization?associationVersion=...`: current active provisioning/association and ownership snapshot for the requested version. It is not a historical-reading validation API.

New domain events carry event_id, event_type, aggregate_id, correlation_id and a non-secret payload. InstallationCompleted includes principal accountId, deviceId and effective completedAt so future Billing can decide its own Free activation; Installation does not create a subscription. Durable transport is at-least-once and uses stable event IDs/idempotency keys.

400 identifies invalid input, 401 missing/invalid current identity, 403 denied role/ownership/function, 404 missing resource, 409 stale/incompatible state and 503 an unconfirmed dependency. Unknown connectivity remains unavailable and never becomes a fabricated measurement.

InstallerAssigned is also emitted after an effective reassignment and includes accountId/installerId. InstallationAppointmentChanged includes accountId, installerId and the effective from/to window; InstallationPaymentVerified includes accountId/purchaseOrderId. These identifiers let future notification consumers resolve recipient identity through IAM's private context contract, without sharing databases or exposing one-time secrets.
