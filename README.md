# Microservices Authentication System

A Spring Boot microservices-based food ordering platform with JWT authentication, API Gateway, and Eureka service discovery.

## Tech Stack

| Technology | Version |
|---|---|
| Java | 25 |
| Spring Boot | 4.1.1 |
| Spring Cloud | 2025.1.3 |
| Spring Security | 7.1.1 |
| Spring Cloud Gateway | 5.0.3 |
| Netflix Eureka | 4.3.3 |
| JJWT (Java JWT) | 0.13.0 |
| PostgreSQL | Runtime |
| Lombok | Latest |
| Maven | 3.9+ |

## Architecture Overview

```mermaid
graph TB
    Client[Client Application]

    subgraph Gateway Layer
        GW[API Gateway<br/>:8080]
    end

    subgraph Service Discovery
        EUREKA[Eureka Server<br/>:8761]
    end

    subgraph Business Services
        ID[Identity Service<br/>:9898]
        ON[Online Service App<br/>:8081]
        REST[Restaurant Service<br/>:8082]
    end

    subgraph Data Layer
        DB[(PostgreSQL<br/>online_db)]
    end

    Client -->|HTTP Request| GW
    GW -->|Service Discovery| EUREKA
    GW -->|/auth/**| ID
    GW -->|/online/**| ON
    GW -->|/restaurant/**| REST
    ID -->|JPA/Hibernate| DB
    ON -->|REST Call via LoadBalancer| REST
    ON -->|Register + Heartbeat| EUREKA
    ID -->|Register + Heartbeat| EUREKA
    REST -->|Register + Heartbeat| EUREKA
    GW -->|Register + Heartbeat| EUREKA

    style GW fill:#f9f,stroke:#333,stroke-width:2px
    style EUREKA fill:#ff9,stroke:#333,stroke-width:2px
    style DB fill:#9cf,stroke:#333,stroke-width:2px
```

## Module Breakdown

### 1. Online Service Registry (Eureka Server)

**Port:** `8761`
**Purpose:** Central service registry where all microservices register themselves for discovery.

```mermaid
graph LR
    S1[Identity Service] -->|register| E[Eureka Server]
    S2[Online Service App] -->|register| E
    S3[Restaurant Service] -->|register| E
    S4[Gateway] -->|register| E
    E -->|heartbeat| S1
    E -->|heartbeat| S2
    E -->|heartbeat| S3
    E -->|heartbeat| S4
```

- Annotated with `@EnableEurekaServer`
- Does not register itself (`register-with-eureka: false`)
- Does not fetch registry (`fetch-registry: false`)
- Dashboard available at `http://localhost:8761`

---

### 2. Identity Service (Authentication & Authorization)

**Port:** `9898`
**Purpose:** Handles user registration, JWT token generation, and token validation.

```mermaid
sequenceDiagram
    participant C as Client
    participant G as Gateway
    participant I as Identity Service
    participant DB as PostgreSQL

    Note over C,DB: Registration Flow
    C->>G: POST /auth/register {name, password, email}
    G->>I: Forward (open endpoint, no auth needed)
    I->>I: BCrypt hash password
    I->>DB: Save UserCredential
    DB-->>I: Saved
    I-->>G: "user added to the system"
    G-->>C: 200 OK

    Note over C,DB: Token Generation Flow
    C->>G: POST /auth/token {username, password}
    G->>I: Forward (open endpoint, no auth needed)
    I->>I: Authenticate via AuthenticationManager
    I->>I: Generate JWT (HS384, 30min expiry)
    I-->>G: JWT Token
    G-->>C: JWT Token

    Note over C,DB: Token Validation Flow (Internal)
    Note right of I: Called by Gateway filter
    I->>I: Validate JWT signature + expiry
    I-->>G: Valid / Invalid
```

**API Endpoints:**

| Method | Endpoint | Auth Required | Description |
|---|---|---|---|
| POST | `/auth/register` | No | Register a new user |
| POST | `/auth/token` | No | Generate JWT token |
| GET | `/auth/validate?token=` | No | Validate a JWT token |

**Key Classes:**

| Class | Responsibility |
|---|---|
| `AuthController` | REST endpoints for register, token, validate |
| `AuthService` | Business logic: save user with BCrypt, delegate token ops |
| `JwtService` | JWT generation & validation using JJWT 0.13.0 API |
| `AuthConfig` | Spring Security 7.x config: STATELESS session, CSRF disabled |
| `CustomUserDetailsService` | Loads user from PostgreSQL for Spring Security |
| `CustomUserDetails` | Implements `UserDetails` with empty authorities |
| `UserCredential` | JPA entity mapped to `user_credential` table |

**Security Configuration:**

```java
http.csrf(csrf -> csrf.disable())
    .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
    .authorizeHttpRequests(auth -> auth
        .requestMatchers("/auth/register", "/auth/token", "/auth/validate").permitAll()
        .anyRequest().authenticated())
```

---

### 3. Online Service App (Customer-Facing Service)

**Port:** `8081`
**Purpose:** Main application service for customers. Provides greeting and order status checking by calling the Restaurant Service.

```mermaid
sequenceDiagram
    participant C as Client
    participant G as Gateway
    participant O as Online Service App
    participant R as Restaurant Service

    C->>G: GET /online/home (with Bearer token)
    G->>G: Validate JWT
    G->>O: Forward request
    O-->>G: "Welcome to Online App Service"
    G-->>C: 200 OK

    C->>G: GET /online/{orderId} (with Bearer token)
    G->>G: Validate JWT
    G->>O: Forward request
    O->>R: GET /restaurant/orders/status/{orderId}<br/>(via RestTemplate + LoadBalancer)
    R-->>O: OrderResponseDTO
    O-->>G: OrderResponseDTO
    G-->>C: OrderResponseDTO JSON
```

**API Endpoints:**

| Method | Endpoint | Auth Required | Description |
|---|---|---|---|
| GET | `/online/home` | Yes | Returns welcome message |
| GET | `/online/{orderId}` | Yes | Checks order status via Restaurant Service |

**Key Classes:**

| Class | Responsibility |
|---|---|
| `OnlineServiceAppController` | REST controller for customer endpoints |
| `OnlineServiceAppService` | Business logic, delegates order lookup to client |
| `RestaurantServiceClient` | Feign-style REST client using `RestTemplate` with `@LoadBalanced` |
| `OnlineServiceAppConfig` | Configures `@LoadBalanced RestTemplate` for service discovery |
| `OrderResponseDTO` | Data transfer object for order details |

**Inter-Service Communication:**

```mermaid
graph LR
    O[Online Service App] -->|RestTemplate + @LoadBalanced| R[Restaurant Service]
    O -->|"http://RESTAURANT-SERVICE/restaurant/orders/status/{id}"| R

    style O fill:#9f9,stroke:#333
    style R fill:#f99,stroke:#333
```

The `@LoadBalanced` annotation on `RestTemplate` enables client-side load balancing. The service name `RESTAURANT-SERVICE` is resolved via Eureka to actual host:port.

---

### 4. Restaurant Service

**Port:** `8082`
**Purpose:** Manages restaurant orders. Returns order details from an in-memory data store.

```mermaid
graph TD
    subgraph Restaurant Service
        C[RestaurantController] --> S[RestaurantService]
        S --> D[RestaurantOrderDAO]
    end

    D -->|In-memory HashMap| M[Mock Order Data]

    M --> O1["35fds631: VEG-MEALS<br/>READY, 15min"]
    M --> O2["9u71245h: HYDERABADI DUM BIRYANI<br/>PREPARING, 59min"]
    M --> O3["37jbd832: PANEER BUTTER MASALA<br/>DELIVERED, 0min"]

    style M fill:#ff9,stroke:#333
```

**API Endpoints:**

| Method | Endpoint | Auth Required | Description |
|---|---|---|---|
| GET | `/restaurant` | Yes (via Gateway) | Returns welcome message |
| GET | `/restaurant/orders/status/{orderId}` | Yes (via Gateway) | Returns order details |

**Key Classes:**

| Class | Responsibility |
|---|---|
| `RestaurantController` | REST controller for restaurant endpoints |
| `RestaurantService` | Business logic for order retrieval |
| `RestaurantOrderDAO` | In-memory data access with mock order data |
| `OrderResponseDTO` | Data transfer object (identical to Online Service's DTO) |

---

### 5. API Gateway

**Port:** `8080`
**Purpose:** Single entry point for all client requests. Routes requests to appropriate services, validates JWT tokens, and enforces security policies.

```mermaid
graph TB
    Client[Client] --> GW{API Gateway<br/>:8080}

    GW -->|"Check route security"| RV[RouteValidator]

    subgraph Security Filter
        RV -->|"/auth/register, /auth/token"| OPEN[Open - Skip Auth]
        RV -->|"/online/**, /restaurant/**"| SECURED[Secured - Validate JWT]
        SECURED -->|Valid Token| ROUTE[Route to Service]
        SECURED -->|No Token| U401[401 Unauthorized]
        SECURED -->|Invalid Token| F403[403 Forbidden]
    end

    ROUTE -->|"lb://IDENTITY-SERVICE"| ID[Identity Service<br/>:9898]
    ROUTE -->|"lb://ONLINE-SERVICE-APP"| ON[Online Service App<br/>:8081]
    ROUTE -->|"lb://RESTAURANT-SERVICE"| REST[Restaurant Service<br/>:8082]

    OPEN --> ID
    OPEN --> ON

    style GW fill:#f9f,stroke:#333,stroke-width:2px
    style U401 fill:#f66,stroke:#333
    style F403 fill:#f96,stroke:#333
    style OPEN fill:#9f9,stroke:#333
    style SECURED fill:#ff9,stroke:#333
```

**Route Configuration (Spring Cloud Gateway 5.x format):**

```yaml
spring.cloud.gateway.server.webflux.routes:
  - id: online-service-app
    uri: lb://ONLINE-SERVICE-APP
    predicates:
      - Path=/online/**
    filters:
      - AuthenticationFilter

  - id: restaurant-service
    uri: lb://RESTAURANT-SERVICE
    predicates:
      - Path=/restaurant/**
    filters:
      - AuthenticationFilter

  - id: identity-service
    uri: lb://IDENTITY-SERVICE
    predicates:
      - Path=/auth/**
```

> **Important:** Spring Cloud Gateway 5.x (Spring Cloud 2025.0+) requires routes under `spring.cloud.gateway.server.webflux.routes`, NOT the legacy `spring.cloud.gateway.routes`.

**Security Filter Flow:**

```mermaid
flowchart TD
    A[Incoming Request] --> B{Is endpoint in<br/>openApiEndpoints list?}
    B -->|Yes| C[Skip Authentication]
    B -->|No| D{Has Authorization<br/>header?}
    D -->|No| E[Return 401 Unauthorized]
    D -->|Yes| F{Starts with<br/>Bearer?}
    F -->|No| E
    F -->|Yes| G[Extract Token]
    G --> H{Validate JWT<br/>with JwtUtil}
    H -->|Valid| I[Forward to Downstream Service]
    H -->|Invalid| J[Return 403 Forbidden]

    C --> I

    style E fill:#f66,color:#fff
    style J fill:#f96,color:#fff
    style I fill:#9f9
```

**Open Endpoints (no auth required):**
- `/auth/register`
- `/auth/token`
- `/eureka`

**Key Classes:**

| Class | Responsibility |
|---|---|
| `AuthenticationFilter` | Custom `AbstractGatewayFilterFactory` - validates JWT on secured routes |
| `RouteValidator` | Predicate that determines if a request needs authentication |
| `JwtUtil` | JWT validation using the shared secret key |
| `AppConfig` | Bean configuration |

---

## Request Flow - Complete Journey

```mermaid
sequenceDiagram
    actor User
    participant GW as API Gateway<br/>:8080
    participant E as Eureka<br/>:8761
    participant ID as Identity Service<br/>:9898
    participant ON as Online Service<br/>:8081
    participant REST as Restaurant Service<br/>:8082
    participant DB as PostgreSQL

    rect rgb(200, 230, 200)
        Note over User,DB: Phase 1: User Registration
        User->>GW: POST /auth/register
        GW->>GW: RouteValidator: /auth/register is open
        GW->>ID: Forward request
        ID->>ID: BCrypt hash password
        ID->>DB: INSERT INTO user_credential
        DB-->>ID: OK
        ID-->>GW: "user added to the system"
        GW-->>User: 200 OK
    end

    rect rgb(200, 220, 240)
        Note over User,DB: Phase 2: Token Generation
        User->>GW: POST /auth/token {username, password}
        GW->>GW: RouteValidator: /auth/token is open
        GW->>ID: Forward request
        ID->>ID: AuthenticationManager.authenticate()
        ID->>DB: SELECT user by name
        DB-->>ID: UserCredential
        ID->>ID: Generate JWT (HS384, 30min)
        ID-->>GW: JWT token
        GW-->>User: JWT token
    end

    rect rgb(240, 220, 200)
        Note over User,REST: Phase 3: Authenticated Request
        User->>GW: GET /online/home<br/>Authorization: Bearer {token}
        GW->>GW: RouteValidator: /online is secured
        GW->>GW: AuthenticationFilter: validate JWT
        GW->>GW: JWT valid
        GW->>E: Resolve ONLINE-SERVICE-APP
        E-->>GW: host:8081
        GW->>ON: Forward request
        ON-->>GW: "Welcome to Online App Service"
        GW-->>User: 200 OK
    end

    rect rgb(240, 200, 220)
        Note over User,REST: Phase 4: Inter-Service Communication
        User->>GW: GET /online/37jbd832<br/>Authorization: Bearer {token}
        GW->>GW: AuthenticationFilter: validate JWT
        GW->>ON: Forward request
        ON->>E: Resolve RESTAURANT-SERVICE
        E-->>ON: host:8082
        ON->>REST: GET /restaurant/orders/status/37jbd832
        REST-->>ON: OrderResponseDTO
        ON-->>GW: OrderResponseDTO
        GW-->>User: JSON response
    end
```

## JWT Token Flow

```mermaid
flowchart LR
    subgraph Token Generation
        A[Client] -->|"POST /auth/token<br/>{username, password}"| B[Identity Service]
        B -->|"BCrypt verify<br/>password"| C[(PostgreSQL)]
        C -->|"User found"| B
        B -->|"Jwts.builder()<br/>.subject(username)<br/>.expiration(+30min)<br/>.signWith(key)"| D[JWT Token]
        D --> A
    end

    subgraph Token Validation
        E[Client] -->|"GET /online/home<br/>Authorization: Bearer {token}"| F[Gateway]
        F -->|"Jwts.parser()<br/>.verifyWith(key)<br/>.parseSignedClaims(token)"| G{Valid?}
        G -->|"Yes"| H[Forward to Service]
        G -->|"No"| I[401/403 Error]
    end

    style D fill:#9f9,stroke:#333
    style H fill:#9f9,stroke:#333
    style I fill:#f66,stroke:#333,color:#fff
```

**JWT Structure:**
- **Algorithm:** HS384 (HMAC-SHA384)
- **Subject:** Username
- **Issued At:** Current timestamp
- **Expiration:** 30 minutes from issuance
- **Secret:** Shared between Identity Service and Gateway

## Database Schema

```mermaid
erDiagram
    USER_CREDENTIAL {
        integer id PK "Auto-generated (IDENTITY strategy)"
        varchar name "Unique username"
        varchar email "User email"
        varchar password "BCrypt hashed password"
    }
```

- **Database:** PostgreSQL (`online_db`)
- **DDL Strategy:** `hibernate.ddl-auto: update` (auto-creates/updates tables)
- **ORM:** Spring Data JPA with Hibernate

## Ports Summary

| Service | Port | URL |
|---|---|---|
| Eureka Server | 8761 | http://localhost:8761 |
| Identity Service | 9898 | http://localhost:9898 |
| Online Service App | 8081 | http://localhost:8081 |
| Restaurant Service | 8082 | http://localhost:8082 |
| API Gateway | 8080 | http://localhost:8080 |

## Getting Started

### Prerequisites

- Java 25
- Maven 3.9+
- PostgreSQL running on `localhost:5432`
- Database `online_db` created with user `postgres` / password `password`

### Database Setup

```sql
CREATE DATABASE online_db;
```

### Build All Modules

```bash
# From each module directory
mvn clean package -DskipTests
```

### Start Services (in order)

```bash
# 1. Eureka Server (must start first)
java -jar online-service-registry/target/online-service-registry-0.0.1-SNAPSHOT.jar

# 2. Identity Service (depends on Eureka + PostgreSQL)
java -jar identity-service/target/identity-service-0.0.1-SNAPSHOT.jar

# 3. Restaurant Service (depends on Eureka)
java -jar restaurant-service/target/restaurant-service-0.0.1-SNAPSHOT.jar

# 4. Online Service App (depends on Eureka + Restaurant Service)
java -jar online-service-app/target/online-service-app-0.0.1-SNAPSHOT.jar

# 5. API Gateway (depends on Eureka + all services)
java -jar online-service-gateway/target/online-service-gateway-0.0.1-SNAPSHOT.jar
```

### Verify Services

```bash
# Check Eureka dashboard
curl http://localhost:8761/actuator/health

# Register a user
curl -X POST http://localhost:8080/auth/register \
  -H "Content-Type: application/json" \
  -d '{"name":"testuser","password":"testpass","email":"test@gmail.com"}'

# Get JWT token
curl -X POST http://localhost:8080/auth/token \
  -H "Content-Type: application/json" \
  -d '{"username":"testuser","password":"testpass"}'

# Access protected endpoint (use token from above)
curl http://localhost:8080/online/home \
  -H "Authorization: Bearer {your-token}"

# Check order status
curl http://localhost:8080/online/37jbd832 \
  -H "Authorization: Bearer {your-token}"
```

## Docker Deployment

### Prerequisites

- Docker
- Docker Compose

### Build and Run

```bash
# Build all images and start all services
docker-compose up --build

# Run in background
docker-compose up --build -d

# Stop all services
docker-compose down

# Stop and remove volumes
docker-compose down -v
```

### Verify

```bash
# Check all containers
docker-compose ps

# Check logs
docker-compose logs -f

# Check specific service logs
docker-compose logs -f gateway
docker-compose logs -f identity-service
```

### Access Services

| Service | URL |
|---|---|
| Eureka Dashboard | http://localhost:8761 |
| API Gateway | http://localhost:8080 |
| Identity Service | http://localhost:9898 |
| Online Service App | http://localhost:8081 |
| Restaurant Service | http://localhost:8082 |

---

## Kubernetes Deployment

### Prerequisites

- Minikube or a Kubernetes cluster
- `kubectl` configured
- Docker images built locally

### Build Images for Kubernetes

```bash
# Point Docker to Minikube's daemon
eval $(minikube docker-env)

# Build all images
docker-compose build
```

### Deploy

```bash
# Apply all resources
kubectl apply -f kubernetes/

# Or apply in order
kubectl apply -f kubernetes/namespace.yaml
kubectl apply -f kubernetes/configmap.yaml
kubectl apply -f kubernetes/secret.yaml
kubectl apply -f kubernetes/postgres.yaml
kubectl apply -f kubernetes/eureka-server.yaml
kubectl apply -f kubernetes/identity-service.yaml
kubectl apply -f kubernetes/gateway.yaml
kubectl apply -f kubernetes/online-service-app.yaml
kubectl apply -f kubernetes/restaurant-service.yaml
```

### Verify Deployment

```bash
# Check pods
kubectl get pods -n microservices-auth

# Check services
kubectl get svc -n microservices-auth

# Check logs
kubectl logs -f deployment/gateway -n microservices-auth
kubectl logs -f deployment/identity-service -n microservices-auth
```

### Access Services via Minikube

```bash
# Get gateway NodePort
minikube service gateway-service -n microservices-auth --url

# Or use port-forward
kubectl port-forward svc/gateway-service 8080:8080 -n microservices-auth
```

### Cleanup

```bash
# Delete all resources
kubectl delete namespace microservices-auth
```

---

## Migration Notes (Spring Boot 4.x / Spring Cloud 2025.x)

This project uses the latest Spring Boot and Spring Cloud versions which include several breaking changes from previous versions:

| Change | Old | New |
|---|---|---|
| Gateway config prefix | `spring.cloud.gateway.routes` | `spring.cloud.gateway.server.webflux.routes` |
| Gateway artifact | `spring-cloud-starter-gateway` | `spring-cloud-starter-gateway-server-webflux` |
| Gateway module | `spring-cloud-gateway-server` | `spring-cloud-gateway-server-webflux` |
| Jackson null-to-primitive | `FAIL_ON_NULL_FOR_PRIMITIVES: false` | `FAIL_ON_NULL_FOR_PRIMITIVES: true` (now rejects `null` → `int`) |
| `UserDetails.getAuthorities()` | Allowed `null` return | Requires non-null `Collection` (use `Collections.emptyList()`) |
| `HttpHeaders.containsKey()` | Available | Removed; use `getFirst()` instead |
| `DaoAuthenticationProvider` | 2-arg constructor | 1-arg constructor only; use `setPasswordEncoder()` setter |
| Session management | Default session creation | Explicit `SessionCreationPolicy.STATELESS` required for REST APIs |

## Project Structure

```
microservices-auth/
├── docker-compose.yml
├── kubernetes/
│   ├── namespace.yaml
│   ├── configmap.yaml
│   ├── secret.yaml
│   ├── postgres.yaml
│   ├── eureka-server.yaml
│   ├── identity-service.yaml
│   ├── gateway.yaml
│   ├── online-service-app.yaml
│   └── restaurant-service.yaml
│
├── identity-service/
│   ├── Dockerfile
│   ├── .dockerignore
│   └── src/main/java/com/auth/microservice/
│       ├── IdentityServiceApplication.java
│       ├── config/
│       │   ├── AuthConfig.java
│       │   ├── CustomUserDetails.java
│       │   └── CustomUserDetailsService.java
│       ├── controller/
│       │   └── AuthController.java
│       ├── dto/
│       │   └── AuthRequest.java
│       ├── entity/
│       │   └── UserCredential.java
│       ├── repository/
│       │   └── UserCredentialRepository.java
│       └── service/
│           ├── AuthService.java
│           └── JwtService.java
│
├── online-service-app/
│   ├── Dockerfile
│   ├── .dockerignore
│   └── src/main/java/com/auth/microservice/
│       ├── OnlineServiceAppApplication.java
│       ├── client/
│       │   └── RestaurantServiceClient.java
│       ├── config/
│       │   └── OnlineServiceAppConfig.java
│       ├── controller/
│       │   └── OnlineServiceAppController.java
│       ├── dto/
│       │   └── OrderResponseDTO.java
│       └── service/
│           └── OnlineServiceAppService.java
│
├── online-service-gateway/
│   ├── Dockerfile
│   ├── .dockerignore
│   └── src/main/java/com/auth/microservice/online_service_gateway/
│       ├── OnlineServiceGatewayApplication.java
│       ├── config/
│       │   └── AppConfig.java
│       ├── filter/
│       │   ├── AuthenticationFilter.java
│       │   └── RouteValidator.java
│       └── util/
│           └── JwtUtil.java
│
├── online-service-registry/
│   ├── Dockerfile
│   ├── .dockerignore
│   └── src/main/java/com/auth/microservice/
│       └── OnlineServiceRegistryApplication.java
│
└── restaurant-service/
    ├── Dockerfile
    ├── .dockerignore
    └── src/main/java/com/auth/microservice/
        ├── RestaurantServiceApplication.java
        ├── controller/
        │   └── RestaurantController.java
        ├── dao/
        │   └── RestaurantOrderDAO.java
        ├── dto/
        │   └── OrderResponseDTO.java
        └── service/
            └── RestaurantService.java
```

## License

This project is for educational purposes.
