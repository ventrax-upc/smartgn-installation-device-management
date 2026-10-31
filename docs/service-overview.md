# Service overview

Installation & Device Management provides properties, supply points, external installer records, provisional and paid installation requests, assignment and appointment history, device associations and confirmed provisioning. IAM supplies current identity and delegated permission checks.

The integrated Docker composition connects the real IAM, Installation, Support and Billing services through the API Gateway, with independent PostgreSQL databases and an authenticated EMQX device broker. Notification delivery and Telemetry metadata use labeled local fixtures. The separate compose.fixtures.yml retains its isolated fixture environment, including a Billing double.

- [Architecture](architecture.md)
- [API contracts](contracts.md)
- [Exported OpenAPI](openapi.json)

Runtime OpenAPI documents request and response schemas. Each service owns its data and communicates through authenticated contracts and events.
