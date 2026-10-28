# Service overview

Installation & Device Management provides properties, supply points, external installer records, provisional and paid installation requests, assignment and appointment history, device associations and confirmed provisioning. IAM supplies current identity and delegated permission checks.

The local Docker environment uses independent PostgreSQL databases and an authenticated EMQX broker. The bundled composition provides labeled development doubles for Billing, Notifications and Telemetry. The integrated Payments environment connects the actual Billing service through its authenticated HTTP contracts.

- [Architecture](architecture.md)
- [API contracts](api/operational-workflows.md)
- [Exported OpenAPI](api/openapi.json)

Runtime OpenAPI documents request and response schemas. Each service owns its data and communicates through authenticated contracts and events.
