# Cab Eye — Docker Setup & Run Guide

This guide explains how to build, run, test, and deploy the Cab Eye backend using Docker, Docker Compose, and AWS.

---

## 1. Prerequisites

Before starting, ensure you have the following installed on your machine:
- **Docker Engine** (v24.0+ or v29.0+)
- **Docker Compose** (v2.20+ or v5.0+)
- **Java 17 or 21** (OpenJDK / Temurin)
- **curl** and **Python 3** (for testing & benchmarking)

Verify your installation:
```bash
docker --version
docker compose version
java -version
```

---

## 2. Quick Start: Local Multi-Instance Cluster (Recommended)

To run the complete production-like architecture locally with **PostgreSQL 16**, **Redis 7**, **Dual Spring Boot App Instances**, and **Nginx Load Balancer**:

### Step 1: Build the backend JAR
```bash
cd backend
./gradlew bootJar
cd ..
```

### Step 2: Build the Docker image
```bash
docker build -t cabeye-backend:latest backend/
```

### Step 3: Start the cluster with Docker Compose
```bash
docker compose up -d
```

### Step 4: Verify container status
```bash
docker compose ps
```
You should see all 5 containers running:
- `cabeye-postgres` (PostgreSQL 16 on private Docker network)
- `cabeye-redis` (Redis 7 on private Docker network)
- `cabeye-app-1` (Spring Boot instance #1 on port `8081`)
- `cabeye-app-2` (Spring Boot instance #2 on port `8082`)
- `cabeye-loadbalancer` (Nginx reverse proxy on port `8088`)

---

## 3. Verifying Health & Readiness

### Liveness Probe (`/health`):
Spoken, fast, non-blocking check used by Android apps and ALB target group liveness:
```bash
curl http://localhost:8088/health
```
**Expected Response:**
```json
{"service":"cabeye-backend","activeRides":0,"openRequests":0,"socketTopics":{},"status":"UP"}
```

### Readiness Probe (`/ready`):
Validates that PostgreSQL and Redis connections are live and ready to receive traffic:
```bash
curl http://localhost:8088/ready
```
**Expected Response:**
```json
{"database":"UP","redis":"UP","status":"READY"}
```

---

## 4. Testing Cross-Instance Synchronization

To confirm that state transitions and authentication sync across nodes in real-time:

### A. Cross-Instance Ride Creation & Acceptance
1. **Create ride on App 1 (Port 8081):**
```bash
curl -X POST http://localhost:8081/rides \
  -H "Content-Type: application/json" \
  -H "X-User-Id: rider-1" \
  -d '{"destination":"Anna Nagar","rideType":"AUTO"}'
```
*(Note the `rideId` from the response, e.g. `ride-1`)*

2. **Accept the ride on App 2 (Port 8082):**
```bash
curl -X POST http://localhost:8082/rides/ride-1/accept \
  -H "Content-Type: application/json" \
  -H "X-User-Id: driver-1" \
  -d '{"driverName":"Murugan","vehicleModel":"Bajaj Auto","vehiclePlate":"TN 01 AB 1234","driverPhone":"9876543210","etaMinutes":3}'
```

3. **Query ride on App 1 (Port 8081):**
```bash
curl http://localhost:8081/rides/ride-1
```
*App 1 immediately reflects `phase: ASSIGNED` and the driver details saved by App 2.*

### B. Distributed OTP Authentication
1. **Request OTP on App 1:**
```bash
curl -X POST http://localhost:8081/auth/otp \
  -H "Content-Type: application/json" \
  -d '{"phone":"9876543210","role":"RIDER"}'
```
*(Copy the `devCode` from the response, e.g. `123456`)*

2. **Verify OTP and Login on App 2:**
```bash
curl -X POST http://localhost:8082/auth/verify \
  -H "Content-Type: application/json" \
  -d '{"phone":"9876543210","role":"RIDER","code":"<devCode>","name":"Mukunth"}'
```
*(Copy the `token` from the response)*

3. **Authenticate on App 1 using Bearer Token:**
```bash
curl http://localhost:8081/me -H "Authorization: Bearer <token>"
```
*App 1 validates the token issued by App 2 and returns the profile.*

---

## 5. Testing Real-Time WebSocket Communication

You can test cross-instance real-time WebSocket propagation using Python:

```bash
python3 - << 'EOF'
import asyncio, json, websockets

async def test_ws():
    ride_id = "test-ride-123"
    # Rider connects to App 1
    async with websockets.connect(f"ws://localhost:8081/ws/ride?rideId={ride_id}&userId=rider-1&role=RIDER") as rider_ws:
        await rider_ws.recv() # CONNECTED ack
        
        # Driver connects to App 2
        async with websockets.connect(f"ws://localhost:8082/ws/ride?rideId={ride_id}&userId=driver-1&role=DRIVER") as driver_ws:
            await driver_ws.recv() # CONNECTED ack
            
            # Rider receives PARTICIPANT_JOINED via Redis
            print("Rider received:", await rider_ws.recv())
            
            # Driver sends location on App 2
            await driver_ws.send(json.dumps({
                "type": "DRIVER_LOCATION",
                "rideId": ride_id,
                "payload": {"distanceMeters": 350, "bearingDeg": 45.0}
            }))
            
            # Rider receives DRIVER_LOCATION on App 1 via Redis
            print("Rider received location:", await rider_ws.recv())

asyncio.run(test_ws())
EOF
```

---

## 6. Running Single-Container Mode (Standalone)

If you only want to run a single standalone container without Docker Compose:

### Option A: Embedded H2 Database (Zero External Dependencies)
```bash
docker run -d \
  --name cabeye-standalone \
  -p 8080:8080 \
  -v cabeye-data:/app/data \
  cabeye-backend:latest
```

### Option B: External PostgreSQL Database
```bash
docker run -d \
  --name cabeye-standalone \
  -p 8080:8080 \
  -e CABEYE_DB_URL="jdbc:postgresql://<db-host>:5432/cabeye" \
  -e CABEYE_DB_USER="<db-user>" \
  -e CABEYE_DB_PASSWORD="<db-password>" \
  -e CABEYE_REDIS_ENABLED="true" \
  -e CABEYE_REDIS_HOST="<redis-host>" \
  cabeye-backend:latest
```

---

## 7. Performance & Load Benchmarking

A load test script is provided in `scripts/load_test.py` to benchmark throughput and latency percentiles (p50, p95, p99):

```bash
# Run benchmark against the local cluster load balancer:
python3 scripts/load_test.py http://localhost:8088
```

Sample output:
```text
=======================================================
 Running Benchmark: 250 requests @ 50 concurrent workers
 Target: http://localhost:8088
=======================================================
Results:
  Total Requests:  250
  Successful:      250 (100.0%)
  Failures:        0
  Throughput:      358.3 req/sec
  p50 Latency:     99.03 ms
  p95 Latency:     235.96 ms
  p99 Latency:     262.59 ms
```

---

## 8. Stopping & Cleaning Up Containers

To stop the Docker Compose cluster and remove containers and network:
```bash
docker compose down
```

To also remove Docker volumes:
```bash
docker compose down -v
```

---

## 9. AWS Production Deployment

To deploy the entire production infrastructure to **AWS Mumbai (`ap-south-1`)**:

1. Ensure AWS CLI is configured with the `cab-eye` profile:
   ```bash
   aws sts get-caller-identity --profile cab-eye
   ```
2. Run the automated deployment script:
   ```bash
   ./aws/deploy.sh
   ```

This script automatically provisions:
- **VPC** (2 Public Subnets, 2 Private Subnets across 2 AZs)
- **Application Load Balancer (ALB)** with HTTP/HTTPS & native WebSocket (WSS) forwarding
- **RDS PostgreSQL 16** (`db.t4g.micro`, 20GB gp3, in private subnets)
- **ElastiCache Redis 7** (`cache.t4g.micro`, in private subnets)
- **ECR Repository** and pushes `cabeye-backend:latest`
- **CloudWatch** structured log group (`/aws/cabeye/backend`)
