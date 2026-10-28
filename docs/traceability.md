# User-story traceability

| Stories / rule | Implementation evidence | Validation |
| --- | --- | --- |
| US01, US02, US03 | Controlled pending account, one-time activation/recovery, current JWT and password mutation | IAM domain/JWT/database tests and joint smoke |
| US04 | Own profile reads/updates and canonical PUT /profiles/me | Profile service/DTO tests and joint smoke |
| US06-US09 | Principal-owned properties/points, versioned updates, logical deactivation and linked device metadata | Property unit tests, real HTTP/PostgreSQL and joint smoke |
| US31 | External installer accreditation, availability, versions and SuperAdmin-only administration | Real HTTP and joint smoke; installer eligibility and reservation tests |
| US32-US34 | Provisional request, payment receipt, target binding, paid automatic/manual assignment, appointments/reassignment | Joint smoke; competing-reservation PostgreSQL test; Cucumber lifecycle fixture |
| US35 | Exact installation/point/installer checks, unique physical serial, protected credentials and confirmed provisioning | Device/worker/gateway tests, PostgreSQL uniqueness and real EMQX smoke |
| US36 | Locked idempotent closure, exact current association, fresh confirmed connectivity and one event | 100 concurrent PostgreSQL closure pairs and 10 HTTP replayed closures in joint smoke |
| US37 | Device metadata, credential rotation/revocation, historical associations and retryable outbox | Device/outbox/database tests and real EMQX smoke |
| US52-US54 | No-default-grant subusers; current independent meter/function checks; principal-only administration | IAM policy/database tests and joint smoke with the same JWT after revocation |
| Pro restriction | Trusted entitlement port on the principal, independent of a function grant | Local Billing double in joint smoke; no Billing implementation claimed |
| Context boundaries | Separate databases, service-only contracts and context-scoped APIs | Joint negative HTTP checks and architecture tests |
| Database integrity | Initial schemas, credentials, profile/token records and assignment history | Isolated-schema persistence tests |

BDD cases use explicit application fixtures, including verified purchase and operational metadata. They are not independent evidence of Niubiz, real Telemetry or provider integration. The joint smoke uses actual IAM JWTs rather than minting substitute JWTs.
