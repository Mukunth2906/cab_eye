# Cab Eye — Production AWS + Docker Architecture
## Antigravity Implementation Specification

> Target: production-oriented Cab Eye deployment for ~1,000 registered users with meaningful concurrent realtime rider/driver activity.
>
> **Critical rule:** Analyze the existing repository first. Do not modify files during Phase 0. Do not rewrite working business logic or introduce infrastructure without evidence.

---

## 1. Existing Backend Baseline

The current backend is:

- Spring Boot 3.3.5
- Java 17 source/target
- Gradle 8.11.1
- Spring Web
- Spring WebSocket
- Spring JDBC
- H2 for local development
- PostgreSQL JDBC driver for production
- Spring Mail
- Razorpay Java SDK
- REST API
- Raw WebSocket endpoint `/ws/ride`
- `RideSessionManager` using an in-memory `ConcurrentHashMap`
- PostgreSQL selected through `CABEYE_DB_URL`
- Server port `8080`

Important existing classes/packages:

```text
com/cabeye/backend/
├── account/
├── admin/
├── auth/
├── camera/
├── config/
├── controller/
├── feedback/
├── memory/
├── model/
├── payment/
├── persistence/
├── service/
├── store/
└── websocket/
```

Deployment-critical classes:

```text
CabEyeBackendApplication.java
DatabaseConfig.java
WebSocketConfig.java
RideSessionManager.java
RideWebSocketHandler.java
AuthInterceptor.java
RideService.java
RidePersistence.java
AccountService.java
PaymentPersistence.java
RazorpayService.java
HealthController.java
```

---

# 2. Target Architecture

Use AWS Mumbai:

```text
ap-south-1
```

Target:

```text
                         INTERNET
                            │
                      HTTPS / WSS
                            │
                            ▼
                 ┌────────────────────┐
                 │ Application Load   │
                 │ Balancer (ALB)     │
                 └─────────┬──────────┘
                           │
                 ┌─────────┴─────────┐
                 ▼                   ▼
        ┌──────────────────┐ ┌──────────────────┐
        │ Docker App #1    │ │ Docker App #2    │
        │ Spring Boot      │ │ Spring Boot      │
        │ Java 17          │ │ Java 17          │
        └────────┬─────────┘ └────────┬─────────┘
                 │                    │
                 └─────────┬──────────┘
                           │
                ┌──────────┴──────────┐
                ▼                     ▼
        ┌────────────────┐    ┌────────────────┐
        │ Redis          │    │ RDS PostgreSQL │
        │ cache          │    │ durable data   │
        │ pub/sub        │    │ users/rides    │
        │ realtime       │    │ payments/etc.  │
        └────────────────┘    └────────────────┘
```

Supporting services:

```text
CloudWatch       → logs, metrics, alarms
Secrets Manager  → credentials/secrets
ACM              → TLS certificates
Route 53         → DNS
ECR              → private Docker images when CI/CD is introduced
```

---

# 3. Core Design Decisions

## KEEP

- Existing Spring Boot application
- Existing REST API
- Existing raw WebSocket protocol
- Existing ride lifecycle
- Existing Android API abstractions
- Existing H2 local development capability
- Existing PostgreSQL support
- Existing business logic
- Existing authentication/payment concepts

## ADD / EVOLVE

- Docker
- Production environment configuration
- RDS PostgreSQL
- Redis for distributed realtime/cache responsibilities
- ALB
- HTTPS/WSS
- CloudWatch
- secure secret injection
- production health/readiness handling
- load testing
- horizontal scaling

## DO NOT INTRODUCE INITIALLY

- Kubernetes/EKS
- Kafka
- microservices
- service mesh
- API Gateway
- Lambda-heavy architecture
- DynamoDB
- unnecessary extra infrastructure

---

# 4. Docker Strategy

Dockerize the Spring Boot backend.

Production:

```text
Docker
  ↓
Spring Boot / Java 17
  ↓
RDS PostgreSQL
```

Do NOT put the production PostgreSQL database inside the application container.

Prefer a multi-stage image:

```text
Stage 1
Gradle + JDK 17
    ↓
build JAR

Stage 2
Java 17 runtime
    ↓
copy JAR
    ↓
run application
```

Use a non-root runtime user where practical.

Expose:

```text
8080
```

Before creating the Dockerfile, inspect:

```text
gradle/wrapper/gradle-wrapper.properties
settings.gradle
build.gradle
CabEyeBackendApplication.java
application.properties
```

Confirm the actual generated JAR name rather than guessing it.

Create `.dockerignore` if absent.

Never bake secrets into the Docker image.

---

# 5. Local vs Production Database

The existing architecture intentionally supports:

```text
LOCAL
Spring Boot → H2 file database

PRODUCTION
Spring Boot → PostgreSQL
```

Preserve this.

The production JDBC URL should be injected through configuration/environment.

Do not force PostgreSQL into every developer workflow unless there is a demonstrated need.

---

# 6. PostgreSQL / RDS

Production database:

```text
Amazon RDS PostgreSQL
```

Requirements:

- private database
- not publicly accessible
- security group permits traffic only from application layer
- encryption enabled
- automated backups
- sensible connection limits
- database named `cabeye`

Inspect `DatabaseConfig.java`, `DataDirectory.java`, `JdbcTable.java`, and persistence classes before changing schema behavior.

Do not automatically add Flyway/Liquibase.

First determine how the current schema is created.

If multiple application instances could race during schema initialization, make schema initialization safe before scaling.

PostgreSQL is the durable source of truth for:

```text
accounts
drivers
rides
payments
feedback
trip history
safety/emergency records
other durable business state
```

---

# 7. Redis

Redis is an acceleration and coordination layer, NOT the permanent database.

Use Redis for:

```text
cross-instance WebSocket event fan-out
driver online/offline presence
latest driver location
short-lived active ride cache where appropriate
rate limiting if required
distributed coordination where required
```

Do NOT store permanent financial or ride records only in Redis.

Architecture:

```text
Spring Boot #1 ─┐
                ├── Redis Pub/Sub
Spring Boot #2 ─┘
```

For driver location:

```text
Driver
  ↓
Spring Boot
  ↓
Redis latest-location state
  ↓
WebSocket
  ↓
Rider
```

Do not write every GPS update directly to PostgreSQL.

Persist location history only when actually required.

Make location-update frequency configurable and avoid excessive battery/data usage.

---

# 8. WebSocket Scaling

The current backend keeps active WebSocket sessions in:

```text
RideSessionManager
ConcurrentHashMap
```

Keep this as a local connection registry.

Do NOT store actual WebSocket session objects in Redis.

Use:

```text
Application instance
    ├── local WebSocket sessions
    └── Redis Pub/Sub
```

Example:

```text
Driver
  ↓
App #1
  ↓
local WebSocket session
  +
Redis publish
  ↓
App #2
  ↓
Rider WebSocket
```

This allows rider and driver connections to land on different application instances behind the ALB.

Inspect carefully:

```text
WebSocketConfig.java
RideWebSocketHandler.java
RideSessionManager.java
RideService.java
```

Verify:

- authentication during WebSocket connection
- ride/user authorization
- cleanup after disconnect
- duplicate connections
- stale sessions
- heartbeat/idle behavior
- event ordering
- event sequence IDs
- replay behavior
- concurrent WebSocket sends
- reconnect behavior
- duplicate Redis events
- cross-instance event delivery

Preserve `/ws/ride` unless analysis proves the protocol itself is inadequate.

---

# 9. Ride State Authority

PostgreSQL must remain authoritative for important ride lifecycle state.

Example:

```text
REQUESTED
ASSIGNED
APPROACHING
ARRIVED
IN_TRIP
COMPLETED
CANCELLED
```

Redis may cache active state.

JVM memory may hold connection-local state.

Android may cache client state.

Ownership rule:

```text
PostgreSQL = source of truth
Redis      = acceleration/coordination
JVM memory = ephemeral connection state
Android    = client state
```

On application restart:

```text
application starts
      ↓
read durable active rides
      ↓
rebuild required transient state
      ↓
accept traffic
```

Critical ride state must never exist only inside `ConcurrentHashMap`.

---

# 10. REST + WebSocket Model

Preserve the current two-channel architecture:

```text
REST
 ↓
commands + snapshots + durable operations

WebSocket
 ↓
low-latency realtime events
```

Android reconnect/recovery:

```text
WebSocket disconnect
        ↓
reconnect
        ↓
REST current-ride snapshot
        ↓
compare sequence/state
        ↓
apply current authoritative state
        ↓
resume WebSocket
```

Do not replay stale announcements blindly.

---

# 11. Idempotency

Production networking can produce duplicate requests.

Review and add idempotency where appropriate for:

```text
ride creation
ride acceptance
ride cancellation
ride start
ride completion
payment order creation
payment confirmation
SOS events
boarding verification
```

A retry must not accidentally:

```text
create duplicate rides
charge twice
complete a ride twice
create duplicate critical events
```

Do not invent an idempotency design that conflicts with the existing API. Inspect first.

---

# 12. Load Balancer

Use AWS Application Load Balancer.

Traffic:

```text
HTTPS → application :8080
WSS   → application :8080
```

The backend instances should not need to be publicly exposed individually.

Use a lightweight health endpoint for target health.

Inspect `HealthController.java` before adding another health system.

Do not depend on sticky sessions as the main scaling mechanism.

---

# 13. HTTPS / WSS

Final production URLs should conceptually be:

```text
https://api.<domain>
wss://api.<domain>/ws/ride
```

Do not use public raw EC2 IPs with plain HTTP/WS for the final production application.

Use:

```text
Route 53
   ↓
ALB
   ↓
ACM certificate
   ↓
Spring Boot
```

Android must only know:

```text
REST API base URL
WebSocket base URL
```

It must NOT know:

```text
RDS
Redis
EC2 internals
AWS infrastructure
```

---

# 14. Android Compatibility

Do not unnecessarily change the Android API contract.

If the current project has a backend URL abstraction, preserve it.

Use environment/build configuration for:

```text
development
staging
production
```

Production should use:

```text
HTTPS
WSS
```

not:

```text
HTTP
WS
```

---

# 15. Authentication / Authorization

Before deployment inspect:

```text
AuthInterceptor
SessionService
OtpService
CurrentAccount
Role
AdminInterceptor
```

Verify:

- rider access boundaries
- driver access boundaries
- admin access boundaries
- ride ownership
- payment ownership
- profile ownership
- WebSocket authorization
- token/session handling
- expired session handling

Never weaken authentication for deployment convenience.

Never expose:

```text
passwords
OTPs
authentication tokens
payment secrets
database credentials
```

in logs.

---

# 16. Secrets

Use secure runtime configuration.

Prefer AWS Secrets Manager for production secrets.

Potential secret categories:

```text
database password
Razorpay secret
mail password
session/signing secret
other third-party credentials
```

Before deployment, scan the repository for accidental credentials.

Do not commit production secrets.

Do not hard-code them in:

```text
Dockerfile
application.properties
Git
Android source
```

---

# 17. Database Performance

Inspect query behavior in:

```text
RideService
RidePersistence
AccountService
MemoryService
PaymentPersistence
FeedbackService
JdbcTable
```

Look for:

- full scans
- repeated queries
- N+1 access
- oversized result sets
- missing indexes
- DB calls on every GPS update
- unnecessary writes

Add indexes based on actual query patterns.

Do not blindly add many indexes.

Tune Hikari connection pools based on measured RDS capacity and number of application instances.

Do not configure an enormous connection pool on every instance.

---

# 18. API Performance

Prioritize:

```text
ride booking
driver assignment
ride state
driver location
boarding verification
SOS
payment
```

Use:

- request validation
- bounded timeouts
- efficient queries
- appropriate response payloads
- structured errors
- request IDs
- proper HTTP status codes

Do not return stack traces or internal exceptions to Android.

---

# 19. Logging / Observability

Use CloudWatch initially.

Useful application fields:

```text
timestamp
requestId
rideId
userId where appropriate
event type
duration
result
```

Do not log every GPS packet at INFO.

Use DEBUG/sampling for high-frequency events.

Monitor:

```text
CPU
memory
request count
4xx
5xx
p50/p95/p99 latency
WebSocket connection count
WebSocket disconnects
Redis errors
DB pool usage
DB latency
application restarts
```

Cab Eye metrics:

```text
booking latency
driver assignment latency
booking success rate
WebSocket reconnect rate
boarding verification failures
SOS events
payment failures
```

---

# 20. Health and Readiness

Separate:

```text
Liveness
```

from:

```text
Readiness
```

Liveness:

```text
Is the process alive?
```

Readiness:

```text
Can this instance safely receive traffic?
```

Inspect the existing `HealthController`.

Do not make the entire application appear dead merely because an optional dependency is temporarily unavailable.

---

# 21. Rate Limiting

Protect:

```text
OTP
login
ride creation
payment
SOS
admin login
feedback
```

Redis can later support distributed rate limiting.

Do not introduce aggressive limits that interfere with legitimate realtime location traffic.

---

# 22. AWS Network Security

Use:

```text
Internet
   ↓
ALB
   ↓
Application security group
   ↓
RDS security group
```

Do NOT expose publicly:

```text
5432 PostgreSQL
6379 Redis
```

SSH should be restricted to administrator IP or use a safer management mechanism.

Only expose required public traffic.

---

# 23. Production Deployment Path

First:

```text
Local
 ↓
Gradle test
 ↓
Gradle bootJar
 ↓
Docker build
 ↓
Docker run
```

Then AWS:

```text
Docker image
 ↓
ECR
 ↓
EC2/container runtime
 ↓
ALB
 ↓
RDS + Redis
```

CI/CD can be introduced after the deployment is stable.

Do not build a complicated pipeline before the local container works.

---

# 24. Scaling Model

Do NOT claim that 1,000 users requires a specific instance type or number of servers.

Distinguish:

```text
1,000 registered users
```

from:

```text
1,000 concurrent active users
```

Capacity must be established through load testing.

The architecture should support:

```text
App #1
App #2
App #3
...
```

behind the ALB.

Scale based on:

```text
CPU
memory
request throughput
p95/p99 latency
WebSocket connections
DB pressure
Redis pressure
```

Start with the smallest practical infrastructure and increase based on measurements.

---

# 25. Load Testing

Before production claims, test:

```text
100 concurrent users
250 concurrent users
500 concurrent users
```

and, where practical:

```text
1,000 concurrent connections
```

Test REST:

```text
login
ride creation
ride status
driver assignment
payment
profile
history
```

Test WebSocket:

```text
connect
authenticate
join ride
driver location
ride event
disconnect
reconnect
duplicate event
out-of-order event
```

Failure tests:

```text
application instance restart
Redis temporary failure
PostgreSQL temporary failure
network interruption
WebSocket reconnect
one application instance becomes unhealthy
```

Measure:

```text
p50
p95
p99
error rate
throughput
reconnect time
DB latency
```

---

# 26. Production Failure Model

If one application instance dies:

```text
ALB
 ↓
remaining healthy instance(s)
```

Durable ride state remains in PostgreSQL.

Redis retains only data appropriate for transient/distributed operation.

Android reconnects and reconciles from the authoritative REST snapshot.

The system must not depend on one JVM's memory for critical business state.

---

# 27. AWS Service Scope

Target services:

```text
EC2
RDS PostgreSQL
ElastiCache Redis
Application Load Balancer
CloudWatch
Secrets Manager
ACM
Route 53
ECR
```

Later, if justified:

```text
Auto Scaling
WAF
CI/CD
```

Not initially:

```text
EKS
Kafka
microservices
service mesh
API Gateway
DynamoDB
OpenSearch
```

---

# 28. Cost Discipline

The AWS account has promotional/free-plan credits, but do not assume every service or configuration is free.

Before provisioning:

- configure AWS budget/alerts
- verify current AWS pricing
- use appropriately sized instances
- stop/delete unused test resources
- avoid unnecessary always-on services
- keep RDS/Redis private
- monitor monthly spend

Correctness and security take priority over forcing every component into a free-tier configuration.

---

# 29. Required Implementation Order

## Phase 0 — ANALYZE ONLY

Do not modify code.

Inspect:

```text
application.properties
DatabaseConfig.java
WebSocketConfig.java
RideSessionManager.java
RideWebSocketHandler.java
AuthInterceptor.java
CabEyeBackendApplication.java
RideService.java
RidePersistence.java
AccountService.java
PaymentPersistence.java
RazorpayService.java
HealthController.java
settings.gradle
gradle-wrapper.properties
build.gradle
```

Produce an architecture report first.

---

## Phase 1 — Production configuration

Only after Phase 0:

- environment-driven configuration
- PostgreSQL production configuration
- Redis abstraction
- secret injection
- health/readiness
- logging improvements

Keep H2 development working.

---

## Phase 2 — Docker

Create only if absent:

```text
Dockerfile
.dockerignore
```

Verify:

```text
./gradlew test
./gradlew bootJar
docker build
docker run
```

---

## Phase 3 — Redis

Add Redis only for:

```text
cross-instance event propagation
driver presence/location cache
distributed coordination where needed
```

Preserve existing WebSocket API.

---

## Phase 4 — AWS

Deploy:

```text
VPC/networking
private RDS
private Redis
ALB
application instances
```

---

## Phase 5 — HTTPS/WSS

Configure:

```text
Route 53
ACM
ALB
HTTPS
WSS
```

Then configure Android production endpoints.

---

## Phase 6 — Observability

Configure:

```text
CloudWatch logs
metrics
alarms
health checks
```

---

## Phase 7 — Load testing

Measure the actual deployment.

Do not claim capacity without measurements.

---

# 30. Required Phase 0 Report

Before changing anything, Antigravity MUST output:

## A. Current architecture

```text
Android
 ↓
REST → Spring Boot → database

Android
 ↓
WebSocket → Spring Boot → RideSessionManager
```

## B. Target architecture

```text
Android
 ↓
ALB
 ↓
Dockerized Spring Boot instances
 ↓
Redis + RDS PostgreSQL
```

## C. Concrete current risks

Only report risks found in source.

## D. File-by-file change plan

Classify each:

```text
KEEP
MODIFY
ADD
REMOVE
```

## E. Environment variable inventory

Use actual source code:

| Variable | Purpose | Local value/source | Production source | Secret? |
|---|---|---|---|---|
| actual variable | actual purpose | actual | actual | Yes/No |

Do not invent variables.

## F. WebSocket scaling design

Explain exactly how:

```text
RideSessionManager
RideWebSocketHandler
WebSocketConfig
Redis
ALB
Android reconnect/reconciliation
```

interact.

## G. Database design

Explain:

```text
H2 local
PostgreSQL production
schema initialization
connection pool
indexes
backup/recovery
```

## H. Deployment sequence

Explain:

```text
Docker
ECR
EC2
ALB
RDS
Redis
Secrets
CloudWatch
HTTPS/WSS
```

---

# 31. Definition of Done

- [ ] Existing backend builds
- [ ] Existing tests pass
- [ ] Docker image builds
- [ ] Container starts
- [ ] H2 local development still works
- [ ] PostgreSQL production works
- [ ] Redis integration works
- [ ] REST works behind ALB
- [ ] WebSocket works behind ALB
- [ ] Multiple app instances can exchange ride events
- [ ] WebSocket reconnect works
- [ ] Ride state survives application restart
- [ ] No production secrets committed
- [ ] RDS is private
- [ ] Redis is private
- [ ] HTTPS works
- [ ] WSS works
- [ ] CloudWatch logs work
- [ ] Health checks work
- [ ] Authentication remains correct
- [ ] Authorization remains correct
- [ ] Payment flow remains correct
- [ ] SOS flow remains correct
- [ ] Driver location flow remains correct
- [ ] Load tests completed
- [ ] p95/p99 latency measured
- [ ] failure/recovery tests completed

---

# 32. Final Antigravity Instruction

This is an existing Cab Eye project, NOT a greenfield project.

Do not rewrite the application.

Do not create files before Phase 0 analysis.

Do not introduce unnecessary services.

Preserve existing REST/WebSocket contracts.

The production direction is:

```text
                CAB EYE

       Rider / Driver Android
                │
          HTTPS + WSS
                │
                ▼
        Application Load
           Balancer
                │
        ┌───────┴───────┐
        ▼               ▼
   Docker App #1   Docker App #2
   Spring Boot     Spring Boot
        │               │
        └───────┬───────┘
                │
        ┌───────┴────────┐
        ▼                ▼
      Redis          RDS PostgreSQL
   realtime/cache     durable data
```

Design for approximately 1,000 registered users and meaningful concurrent realtime activity, but determine actual capacity through load testing.

Optimize for:

```text
FAST
RESPONSIVE
RELIABLE
SECURE
SCALABLE
OBSERVABLE
MAINTAINABLE
```

while keeping the architecture understandable and avoiding premature complexity.
