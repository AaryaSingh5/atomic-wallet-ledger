# PayCore Ledger — Distributed Double-Entry Wallet & Payment Engine

> An ACID-compliant, event-driven digital wallet and double-entry ledger service built with **Java 17, Spring Boot 3, PostgreSQL 16, Apache Kafka (KRaft), and Redis 7**. Engineered to prevent double-spending under high concurrency, guarantee crash-safe event publishing via the **Transactional Outbox pattern**, and protect payment endpoints using **atomic Redis Lua rate limiting**.

---

## Table of Contents

- [Key Engineering Highlights](#key-engineering-highlights)
- [Architecture](#architecture)
- [Project Structure](#project-structure)
- [API Reference](#api-reference)
- [Design Decisions & Guarantees](#design-decisions--guarantees)
  - [Double-Entry Bookkeeping](#double-entry-bookkeeping)
  - [Concurrency & Idempotency](#concurrency--idempotency)
  - [Transactional Outbox → Kafka](#transactional-outbox--kafka)
  - [Redis Sliding-Window Rate Limiter](#redis-sliding-window-rate-limiter)
- [Running Locally](#running-locally)
  - [Docker Compose (recommended)](#docker-compose-recommended)
  - [Run Without Docker](#run-without-docker)
- [Load Testing (k6)](#load-testing-k6)
- [Configuration Reference](#configuration-reference)

---

## Key Engineering Highlights

| Concern | Mechanism |
|---|---|
| **Money correctness** | Double-entry bookkeeping — every movement creates one `DEBIT` + one `CREDIT` ledger entry of equal amount |
| **Overdraft prevention** | `SELECT ... FOR UPDATE` row locks + DB `CHECK (balance >= 0)` constraint |
| **Deadlock prevention** | Locks always acquired in ascending UUID order (global canonical ordering) |
| **Duplicate charges** | Per-user idempotency key hashed against request fingerprint; replayed on retry |
| **Crash-safe messaging** | Transactional outbox: event written in the **same DB transaction** as the ledger mutation, relayed to Kafka after commit |
| **Endpoint protection** | Atomic Lua sliding-window rate limiter on Redis Sorted Sets, fail-open if Redis is unavailable |
| **Balance audit** | `ReconciliationService` verifies `Σ debits == Σ credits`, per-account balance matches ledger, and `Σ all balances == 0` |
| **Schema evolution** | Flyway-managed migrations — Hibernate never owns the schema |

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                           HTTP Client                               │
└──────────────────────────────┬──────────────────────────────────────┘
                               │ HTTPS :8080
                               ▼
┌──────────────────────────────────────────────────────────────────────┐
│                        Spring Boot App                               │
│                                                                      │
│  RateLimitFilter (Redis Lua)  →  JwtAuthFilter  →  Controllers       │
│                                                                      │
│  WalletController  ──▶  WalletService  ──▶  PostgreSQL               │
│       │                      │                                       │
│       │               ┌──────┴──────┐                               │
│       │               │  Same TX    │                               │
│       │        wallet_transactions  │                               │
│       │        ledger_entries       │  (double-entry)               │
│       │        outbox_events ───────┘                               │
│       │                                                              │
│  OutboxRelayService (@Scheduled 500 ms)                              │
│       └──▶  Kafka Producer  ──▶  topic: wallet.transaction.created   │
│                                                                      │
│  ReconciliationService  ──▶  balance invariant checks               │
└──────────────────────────────────────────────────────────────────────┘
         │                   │                   │
         ▼                   ▼                   ▼
   PostgreSQL 16         Redis 7           Kafka (KRaft)
  (ledger store)      (rate limiter)    (event streaming)
```

**Data flow for a transfer:**

1. `RateLimitFilter` checks the Redis ZSET sliding window → 429 if exceeded.
2. `JwtAuthFilter` validates the Bearer token → 401 if invalid.
3. `WalletService.transfer()` acquires row locks on both accounts (lowest UUID first).
4. Idempotency key is checked inside the lock window to avoid TOCTOU races.
5. Balances are updated, `WalletTransaction` + two `LedgerEntry` rows + one `OutboxEvent` are inserted — all in a single atomic DB transaction.
6. `OutboxRelayService` polls every 500 ms, publishes to Kafka, then deletes the outbox row.

---

## Project Structure

```
src/main/java/com/example/wallet/
├── api/
│   ├── AuthController.java          # /api/auth/register, /api/auth/login
│   ├── WalletController.java        # /api/wallet — topup, transfer, history
│   ├── ReconciliationController.java# /api/reconcile
│   ├── Dtos.java                    # Request/response records
│   ├── ApiException.java            # Typed HTTP errors
│   └── GlobalExceptionHandler.java  # @ControllerAdvice → ErrorResponse
├── domain/
│   ├── Account.java                 # Wallet account (holds balance)
│   ├── LedgerEntry.java             # Immutable DEBIT/CREDIT entry
│   ├── WalletTransaction.java       # Business operation record
│   ├── OutboxEvent.java             # Transactional outbox row
│   └── User.java
├── messaging/
│   └── OutboxRelayService.java      # @Scheduled Kafka relay
├── ratelimit/
│   ├── RateLimitFilter.java         # Redis Lua sliding-window filter
│   └── RateLimitProperties.java     # Externalised thresholds
├── repo/                            # Spring Data JPA repositories
├── security/
│   ├── JwtService.java              # JWT sign / verify (HMAC-SHA256)
│   ├── JwtAuthFilter.java           # Stateless token filter
│   └── SecurityConfig.java          # Filter chain wiring
├── service/
│   ├── WalletService.java           # Core money movement logic
│   ├── AuthService.java             # Register / login
│   └── ReconciliationService.java   # Ledger invariant checks
└── WalletApplication.java

src/main/resources/
├── application.yml
└── db/migration/
    ├── V1__init.sql                 # Core schema (users, accounts, ledger)
    └── V2__outbox.sql               # outbox_events table

loadtest/
└── transfer.js                      # k6 P2P transfer load test
```

---

## API Reference

All amounts are in **paise** (1 INR = 100 paise). All write endpoints require an `Idempotency-Key` header.

### Auth

| Method | Path | Body | Description |
|--------|------|------|-------------|
| `POST` | `/api/auth/register` | `{name, email, password}` | Create user + wallet account |
| `POST` | `/api/auth/login` | `{email, password}` | Returns `{token}` JWT |

### Wallet

All wallet endpoints require `Authorization: Bearer <token>`.

| Method | Path | Headers | Body | Description |
|--------|------|---------|------|-------------|
| `GET` | `/api/wallet` | — | — | Current balance |
| `POST` | `/api/wallet/topup` | `Idempotency-Key` | `{amount, note?}` | Credit wallet (from platform account) |
| `POST` | `/api/wallet/transfer` | `Idempotency-Key` | `{receiverEmail, amount, note?}` | P2P transfer |
| `GET` | `/api/wallet/transactions` | — | `?page=0&size=20` | Paginated ledger history |

### Reconciliation

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/reconcile` | Full balance audit report |

**Example reconciliation response:**
```json
{
  "balanced": true,
  "totalDebits": 50000000,
  "totalCredits": 50000000,
  "sumOfAllBalances": 0,
  "accountMismatches": [],
  "unbalancedTransactions": []
}
```

---

## Design Decisions & Guarantees

### Double-Entry Bookkeeping

Every money movement writes exactly **two** `LedgerEntry` rows of equal `amount`:

```
TOPUP ₹500:  SYSTEM_FUNDING  →DEBIT  500  |  user_wallet  →CREDIT  500
TRANSFER ₹1: sender_wallet   →DEBIT    1  |  recv_wallet  →CREDIT    1
```

The `ReconciliationService` enforces three invariants continuously:

1. `Σ(all debits) == Σ(all credits)` across the entire ledger.
2. Per-account `balance == Σ(credits) - Σ(debits)` from `ledger_entries`.
3. `Σ(all account balances) == 0` — money is never created or destroyed.

The k6 teardown phase calls `/api/reconcile` after every load run to validate these hold under concurrent writes.

### Concurrency & Idempotency

**Lock ordering** prevents A→B / B→A deadlocks:

```java
// Always lock the lower UUID first, regardless of transfer direction
List<UUID> ordered = first.compareTo(second) <= 0
    ? List.of(first, second) : List.of(second, first);
```

**Idempotency** is checked _inside_ the lock window so a concurrent retry with the same key cannot race past the check and produce a duplicate transaction. The check compares a SHA-256 fingerprint of `(type, debitAccount, creditAccount, amount)` to detect key reuse with a different payload.

A `UNIQUE (initiator_user_id, idempotency_key)` constraint in the DB acts as the final backstop.

### Transactional Outbox → Kafka

The classic problem: how do you atomically update the DB _and_ publish to Kafka without a distributed transaction?

**Solution — Transactional Outbox:**

```
WalletService.execute() — single DB transaction:
  INSERT INTO wallet_transactions ...
  INSERT INTO ledger_entries (×2) ...
  INSERT INTO outbox_events (payload = {transactionId, type, amount, ...})
  COMMIT

OutboxRelayService (@Scheduled, every 500 ms):
  SELECT * FROM outbox_events ORDER BY created_at LIMIT 100
  for each row:
    kafka.send(topic=event_type, key=aggregate_id, value=payload)
    DELETE FROM outbox_events WHERE id = row.id
```

- **At-least-once delivery** — if the app crashes between `send()` and `DELETE`, the row survives and is re-sent on restart.
- **Ordering** — topic key = `transactionId` guarantees all events for a given transaction land in the same Kafka partition, preserving order.
- **Idempotent producer** — `enable.idempotence=true` + `acks=all` prevents broker-side duplicates from retries.
- **Production note:** replace polling with [Debezium CDC](https://debezium.io/) for sub-millisecond latency and zero DB polling overhead.

### Redis Sliding-Window Rate Limiter

Atomic Lua script on a Redis Sorted Set, evaluated in a single round-trip:

```lua
ZREMRANGEBYSCORE key -inf (now - windowMs)   -- evict expired entries
count = ZCARD key
if count < limit:
    ZADD key now member                       -- add this request
    PEXPIRE key ttlMs
    return 1  -- allowed
return 0      -- rejected → 429
```

| Bucket | Endpoints | Default limit |
|--------|-----------|---------------|
| `write` | `/topup`, `/transfer` | 20 req / 10 s per user |
| `read` | All others | 100 req / 60 s per user |

- Key = `rate:<userId>:<bucket>` (falls back to client IP for unauthenticated requests).
- **Fail-open:** if Redis is unreachable, the filter logs a warning and passes the request through — Kafka/DB remain the authority.
- All thresholds are externalised in `app.rate-limit.*` and can be overridden per environment without recompilation.

---

## Running Locally

### Docker Compose (recommended)

Requires Docker Desktop ≥ 4.x.

```bash
# Clone
git clone https://github.com/AaryaSingh5/atomic-wallet-ledger.git
cd atomic-wallet-ledger

# Start Postgres, Redis, Kafka (KRaft), and the app
docker compose up --build

# App is ready at http://localhost:8080
```

All services start with healthchecks; the app waits until Postgres, Redis, and Kafka are healthy before accepting traffic.

### Run Without Docker

Prerequisites: Java 17+, Maven 3.9+, PostgreSQL 16, Redis 7, Kafka 3.7+ (KRaft mode).

```bash
# 1. Start Postgres, Redis, and Kafka externally

# 2. Set environment variables (or edit application.yml)
export DB_URL=jdbc:postgresql://localhost:5432/wallet
export DB_USER=wallet
export DB_PASSWORD=wallet
export REDIS_HOST=localhost
export KAFKA_BOOTSTRAP=localhost:9092
export JWT_SECRET=change-me-change-me-change-me-change-me-32b

# 3. Build and run
mvn spring-boot:run
```

Flyway runs migrations automatically on startup (`V1__init.sql`, `V2__outbox.sql`).

#### Quick smoke test

```bash
# Register
curl -s -X POST http://localhost:8080/api/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"name":"Alice","email":"alice@example.com","password":"password123"}' | jq .

# Login → copy the token
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"alice@example.com","password":"password123"}' | jq -r .token)

# Top-up ₹500 (50000 paise)
curl -s -X POST http://localhost:8080/api/wallet/topup \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Idempotency-Key: topup-001' \
  -H 'Content-Type: application/json' \
  -d '{"amount":50000,"note":"first top-up"}' | jq .

# Check balance
curl -s http://localhost:8080/api/wallet \
  -H "Authorization: Bearer $TOKEN" | jq .

# Reconciliation audit
curl -s http://localhost:8080/api/reconcile \
  -H "Authorization: Bearer $TOKEN" | jq .
```

---

## Load Testing (k6)

Requires [k6](https://k6.io/docs/get-started/installation/).

```bash
# 20 VUs, 30 s, each VU uses its own sender/receiver pair (no hot-row contention)
k6 run loadtest/transfer.js

# High-contention worst case (all VUs hammer one wallet pair)
k6 run -e PAIRS=1 loadtest/transfer.js

# Target a remote instance
k6 run -e BASE_URL=http://your-server:8080 loadtest/transfer.js
```

**What the script validates:**

| Check | Assertion |
|-------|-----------|
| Transfer HTTP status | `201 Created` per unique idempotency key |
| p95 latency threshold | `< 300 ms` |
| Error rate threshold | `< 1%` |
| Post-run reconciliation | `balanced == true` (teardown phase calls `/api/reconcile`) |

> **Share your numbers!** Run `k6 run loadtest/transfer.js` and add your p50/p95/RPS results to this table.

---

## Configuration Reference

All settings are in [`src/main/resources/application.yml`](src/main/resources/application.yml) and overridable via environment variables.

| Property | Env var | Default | Description |
|----------|---------|---------|-------------|
| `spring.datasource.url` | `DB_URL` | `jdbc:postgresql://localhost:5432/wallet` | JDBC connection string |
| `spring.datasource.username` | `DB_USER` | `wallet` | DB user |
| `spring.datasource.password` | `DB_PASSWORD` | `wallet` | DB password |
| `spring.data.redis.host` | `REDIS_HOST` | `localhost` | Redis host |
| `spring.data.redis.port` | `REDIS_PORT` | `6379` | Redis port |
| `spring.kafka.bootstrap-servers` | `KAFKA_BOOTSTRAP` | `localhost:9092` | Kafka bootstrap |
| `app.jwt.secret` | `JWT_SECRET` | *(insecure default)* | HMAC-SHA256 signing key — **must be ≥ 32 bytes in production** |
| `app.jwt.expiration-minutes` | — | `60` | JWT TTL |
| `app.outbox.relay-interval-ms` | — | `500` | Outbox polling interval (ms) |
| `app.rate-limit.write-limit` | — | `20` | Max requests per write window |
| `app.rate-limit.write-window-ms` | — | `10000` | Write window size (ms) |
| `app.rate-limit.read-limit` | — | `100` | Max requests per read window |
| `app.rate-limit.read-window-ms` | — | `60000` | Read window size (ms) |

---

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Language | Java 17 |
| Framework | Spring Boot 3.2 |
| Persistence | PostgreSQL 16, Spring Data JPA, Hibernate |
| Schema migrations | Flyway |
| Messaging | Apache Kafka 3.7 (KRaft — no ZooKeeper) |
| Rate limiting | Redis 7 (Sorted Sets + atomic Lua) |
| Security | Spring Security, JJWT 0.12 (HMAC-SHA256) |
| Containerisation | Docker, Docker Compose |
| Load testing | k6 |

---

<p align="center">
  <img src="https://img.shields.io/badge/Java-17-orange?logo=openjdk" />
  <img src="https://img.shields.io/badge/Spring%20Boot-3.2-6DB33F?logo=springboot&logoColor=white" />
  <img src="https://img.shields.io/badge/PostgreSQL-16-4169E1?logo=postgresql&logoColor=white" />
  <img src="https://img.shields.io/badge/Kafka-KRaft-231F20?logo=apachekafka&logoColor=white" />
  <img src="https://img.shields.io/badge/Redis-7-DC382D?logo=redis&logoColor=white" />
  <img src="https://img.shields.io/badge/Docker-Compose-2496ED?logo=docker&logoColor=white" />
  <img src="https://img.shields.io/badge/k6-load%20tested-7D64FF?logo=k6&logoColor=white" />
</p>
