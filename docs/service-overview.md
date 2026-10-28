# Service overview

Installation & Device Management provides properties, supply points, external installer records, provisional and paid installation requests, assignment and appointment history, device associations and confirmed provisioning. IAM supplies current identity and delegated permission checks.

The services run locally in Docker with independent PostgreSQL databases and a real EMQX broker. Billing, Notifications and Telemetry integrations use labeled local contract doubles.

- [Architecture](architecture.md)
- [User-story traceability](traceability.md)
- [Executed verification](verification.md)

Runtime OpenAPI documents the available operations. The verification record distinguishes executed application/broker checks from simulated integrations and unverified provider behavior.
