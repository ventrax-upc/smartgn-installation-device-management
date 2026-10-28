# Architecture

SmartGN consists of seven bounded contexts. IAM owns identities, credentials, profiles and delegated permissions. Installation & Device Management owns properties, supply points, external installer records, installation requests, devices and their operational histories. The report and C4, class and database diagrams define the service responsibilities and public contracts.

## Service responsibilities

| Area | Current design |
| --- | --- |
| Platform | Java 25, Spring Boot, independent PostgreSQL databases and local Docker execution |
| Identity | USER and SUPERADMIN global roles; principal/delegated account kinds; pending, active and disabled account states |
| Credentials | Separate password credentials, purpose-bound one-time tokens and current session versions |
| Delegation | Principal-owned subusers and independently revocable meter/function grants |
| Installation | Provisional contact requests, verified purchase receipts, target linking, appointments and paid-only assignment |
| Installers | External catalogue records with accreditation, availability and assignment history; no IAM access accounts |
| Devices | Versioned associations, encrypted credentials, confirmed provisioning, connectivity checks, rotation and revocation |
| Integration | Service credentials, transactional inbox/outbox, deduplication and correlation; no shared database access |

Public REST contracts determine methods and resources; acceptance criteria determine domain invariants. A supply-point UUID identifies a stable logical meter. Device replacement does not change that meter's permissions.

## Authorization and service boundaries

IAM verifies meter ownership through Installation's private read contract. That contract resolves local data without calling IAM. Installation verifies JWT signatures, issuer, audience and expiry, then introspects current identity and session state. Delegated reads additionally require a current function grant. Dependency failures deny authorization.

Properties and supply points belong to the principal. Delegates can access granted meter functions without acquiring property administration rights. Automatic assignment uses a service audit marker rather than an IAM login account.

Support & Maintenance, Payments & Billing, Monitoring & Analytics, Telemetry & Data Ingestion and Alerting & Notifications own their respective capabilities. Development fixture routes under `/local-contracts/` and `/fixtures/` provide replaceable local doubles for those integrations.
