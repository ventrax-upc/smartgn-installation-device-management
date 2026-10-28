# Device lifecycle and replaceable Telemetry port

Installation owns device identity, unique hardware serials, association versions, credential references, provisioning evidence and broker administration. Telemetry owns original readings and ingestion. Monitoring owns derived consumption/report queries; this service exposes no consumption query port.

Registration validates the exact in-progress installation, customer, property, point and assigned installer. Credentials are random and individually protected; creation/rotation returns the plaintext only once with no-store. Query APIs never return it. A durable leased job stages deny-by-default broker credentials, synchronizes the exact association and only then enables the device's publish ACL. It records provisioning evidence after both confirmations. Failed operations remain retryable and are not reported as active.

EMQX authorization is restricted to `smartgn/devices/{deviceId}/telemetry` with QoS 1. Rotation/revocation disconnect existing sessions and update authorization. The tests exercise a real local broker, including forbidden topics and old credentials. This does not prove actual Telemetry ingestion or physical firmware behavior.

Association versions and validity intervals survive replacement. Reassociation requires an explicit confirmed zero-pending buffer drain with evidence; missing state is unavailable, never an assumed empty buffer. Administrative gap resolution requires a reason. Older intervals are closed/synchronized without rewriting their ownership.

## Provisional Telemetry adapter

These wire shapes are development contracts inherited/refined for local tests. The real future Telemetry service must ratify them; they are not asserted to be its definitive API.

- POST `/internal/v1/device-associations`: deviceId, version, accountId, propertyId, pointId, validFrom and optional validTo; acknowledgement identifies the exact device/version with confirmed=true.
- POST `/internal/v1/devices/status-query`: accountId, deviceIds, purpose=DEVICE_OPERATIONS; returns deviceId, connectionState and lastContact only. Responses outside requested scope are rejected.
- POST `/internal/v1/devices/{id}/buffer-drain`: associationVersion; confirmed drain requires drained=true, pendingReadings=0 and a nonempty evidenceId.

Calls have bounded timeouts, a service credential and X-Correlation-ID. The local SQLite simulator supplies metadata/buffer facts only when explicitly provided by authenticated fixture input. It does not ingest MQTT or calculate readings. Missing last contact never becomes a current observation. Completion requires CONNECTED metadata with lastContact less than 30 seconds old and no more than 5 seconds in the future; the exact device version is rechecked under the closure lock.

Current publication authorization and historical reading attribution are separate concerns. Future Telemetry must preserve the association version/timestamps of buffered historical packets and use its confirmed association ledger. General domain events carry no device secrets; provisioning work stores only protected references.
