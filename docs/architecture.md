# Architecture

Installation & Device Management owns properties, supply points, external installer records, installation requests, devices and their operational histories. Its independent PostgreSQL database stores these resources; IAM supplies identity and delegated authorization, and Billing confirms purchases.

## Service responsibilities

| Area | Current design |
| --- | --- |
| Platform | Java 25, Spring Boot, independent PostgreSQL databases and local Docker execution |
| Installation | Provisional contact requests, verified purchase receipts, target linking, appointments and paid-only assignment |
| Installers | External catalogue records with accreditation, availability and assignment history; no IAM access accounts |
| Devices | Versioned associations, encrypted credentials, confirmed provisioning, connectivity checks, rotation and revocation |
| Integration | Service credentials, transactional inbox/outbox, deduplication and correlation; no shared database access |

Public REST contracts expose resource operations and validation. A supply-point UUID identifies a stable logical meter. Device replacement does not change that meter's permissions.

## Authorization and service boundaries

IAM verifies meter ownership through Installation's private read contract. That contract resolves local data without calling IAM. Installation verifies JWT signatures, issuer, audience and expiry, then introspects current identity and session state. Delegated reads additionally require a current function grant. Dependency failures deny authorization.

Properties and supply points belong to the principal. Delegates can access granted meter functions without acquiring property administration rights. Automatic assignment uses a service audit marker rather than an IAM login account.

The integrated composition connects real IAM, Installation, Support and Billing services through the Gateway. Notification delivery and Telemetry metadata use explicitly labeled local fixtures; they do not provide productive notification delivery or telemetry ingestion.
