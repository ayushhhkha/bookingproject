# Booking System

A booking backend built with **Spring Boot, PostgreSQL, Flyway, JWT authentication, Redis and Kafka**.
Core promises:

1. **A slot can never be booked twice**, even when many people click at the same moment (enforced by the database).
2. **Only logged-in users can book**, only admins can create resources, and you can only cancel your own bookings.
3. **Fast and protected**: availability is cached in Redis, slots can be held for 10 minutes while paying, and login/booking are rate limited.
4. **Reliable events**: every booking and cancellation produces an event (transactional outbox -> Kafka), and a consumer turns it into a notification, exactly once in effect even if Kafka delivers twice.
5. **Redis and Kafka are optional for correctness**: if either goes down, bookings still work and PostgreSQL stays the source of truth.

New to the project? Read [`MASTER.md`](MASTER.md): it explains every file and decision from the beginning, and has a bug log (section 39).

## Status

- [x] Phase 1: resources, slots, bookings, double-booking protection, concurrency test
- [x] Phase 1b: users, registration/login, JWT, roles, ownership rules, HTTP-level tests
- [x] Phase 2: Redis (availability cache, slot holds with TTL, rate limiting)
- [x] Phase 3: Kafka (transactional outbox, idempotent consumer, retries, dead-letter topic)
- [ ] Phase 4: full Docker Compose, CI, metrics, load test, deployment

## Setup (Windows + WSL2 Ubuntu)

1. Install **Docker Desktop** on Windows and enable *Settings > Resources > WSL Integration* for Ubuntu.
2. In Ubuntu:

```bash
sudo apt update && sudo apt install -y zip unzip curl git jq
curl -s "https://get.sdkman.io" | bash
source "$HOME/.sdkman/bin/sdkman-init.sh"
sdk install java 21-tem
sdk install maven
```

3. Check: `java -version` (21), `mvn -version`, `docker --version`.

Keep the project in `~/projects`, not under `/mnt/c` (much faster), and open the folder that directly contains `pom.xml` in your editor.

## Run

```bash
docker compose up -d        # PostgreSQL + Redis + Kafka
docker compose ps           # all three should be "running"
mvn spring-boot:run         # API on http://localhost:8080
```

Kafka needs a few seconds to start. If the app logs "Broker may not be available" right at startup, wait a moment: it keeps retrying.

On first start an admin account is created: **admin@example.com / admin12345** (change it in `application.yml`
or via environment for anything real).

Migration `V3` is new, so your existing database upgrades automatically. Nothing to reset.
(Full reset if you ever want one: `docker compose down -v && docker compose up -d`.)

## Test

```bash
mvn test    # Docker must be running; starts temporary PostgreSQL, Redis and Kafka containers
```

**Before the first run, check that no test file ended up in the main source folder** (the most common mistake in this project):

```bash
find src/main -iname "*test*"        # must print NOTHING
find src/test -name "*.java"         # must list: IntegrationTestBase, DoubleBookingTest, AuthFlowTest,
                                     #            RedisFeaturesTest, RateLimitHttpTest, KafkaOutboxTest
```

If a `*Test.java` file shows up under `src/main`, move it to `src/test/java/com/example/booking/`
(error symptom: `package org.junit.jupiter.api does not exist`; see `MASTER.md`, bug B2/B5).

| Test | What it proves |
|---|---|
| `DoubleBookingTest` | 50 users book one slot simultaneously; exactly one wins |
| `AuthFlowTest` | public vs protected endpoints, roles (403), ownership, conflicts (409), cancel and rebook |
| `RedisFeaturesTest` | cache fill + eviction, holds block others, 20 simultaneous holds -> 1 winner, rate limiter counts |
| `RateLimitHttpTest` | the 4th login attempt from one IP gets 429 with a `Retry-After` header |
| `KafkaOutboxTest` | event written with the booking, published, consumed into one notification; duplicate delivery skipped; double cancel = one event; failed booking = no event; poison message lands in the dead-letter topic |

The first run downloads the Postgres, Redis and Kafka images and is slow; later runs are fast.

## Try it (copy-paste walkthrough)

```bash
API=localhost:8080/api
JSON='Content-Type: application/json'

# 1. log in as admin and keep the token
ADMIN=$(curl -s -X POST $API/auth/login -H "$JSON" \
  -d '{"email":"admin@example.com","password":"admin12345"}' | jq -r .token)

# 2. admin creates a resource and a slot (use a future date!)
curl -s -X POST $API/resources -H "$JSON" -H "Authorization: Bearer $ADMIN" -d '{"name":"Tennis Court 1"}'
curl -s -X POST $API/resources/1/slots -H "$JSON" -H "Authorization: Bearer $ADMIN" \
  -d '{"startTime":"2026-12-01T10:00:00Z","endTime":"2026-12-01T11:00:00Z"}'

# 3. register and log in as two normal users
curl -s -X POST $API/auth/register -H "$JSON" -d '{"email":"alice@example.com","password":"password123","name":"Alice"}'
curl -s -X POST $API/auth/register -H "$JSON" -d '{"email":"bob@example.com","password":"password123","name":"Bob"}'
ALICE=$(curl -s -X POST $API/auth/login -H "$JSON" -d '{"email":"alice@example.com","password":"password123"}' | jq -r .token)
BOB=$(curl -s -X POST $API/auth/login -H "$JSON" -d '{"email":"bob@example.com","password":"password123"}' | jq -r .token)

# 4. book (run twice: the second gives 409), see your bookings, cancel
curl -s -X POST $API/bookings -H "$JSON" -H "Authorization: Bearer $ALICE" -d '{"slotId":1}'
curl -s $API/bookings/mine -H "Authorization: Bearer $ALICE"
curl -s -X POST $API/bookings/1/cancel -H "Authorization: Bearer $ALICE"
```

### See the Kafka pipeline working (Phase 3)

```bash
# After booking and cancelling above, wait ~2 seconds, then:

# a) Alice's notifications (created by the consumer from the events)
curl -s $API/notifications/mine -H "Authorization: Bearer $ALICE" | jq

# b) the "emails": look in the terminal running the app for lines starting with  EMAIL to=alice@example.com

# c) the outbox table: every event, and when it was published
docker exec -it booking-postgres psql -U booking -d booking \
  -c "SELECT id, event_type, aggregate_id, published_at FROM outbox_events ORDER BY id;"

# d) the raw messages on the Kafka topic
docker exec -it booking-kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic booking-events --from-beginning --timeout-ms 5000

# e) consumer group progress (LAG 0 = fully caught up)
docker exec -it booking-kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 --describe --group notification-service
```

### What happens if Kafka dies? (the point of the outbox)

```bash
docker compose stop kafka
curl -s -X POST $API/bookings -H "$JSON" -H "Authorization: Bearer $BOB" -d '{"slotId":1}'   # still works!
# the app log now warns every second: "Could not publish outbox event ..."
docker exec -it booking-postgres psql -U booking -d booking \
  -c "SELECT count(*) AS waiting FROM outbox_events WHERE published_at IS NULL;"   # events are waiting
docker compose start kafka
# within a few seconds the waiting events are published and Bob's notification appears
curl -s $API/notifications/mine -H "Authorization: Bearer $BOB" | jq
```

### A poison message and the dead-letter topic

```bash
# send something that is not valid JSON straight into the topic
docker exec -it booking-kafka bash -c \
  "echo 'poison:this-is-not-json' | /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 --topic booking-events --property parse.key=true --property key.separator=:"

# it is parked in the dead-letter topic instead of blocking everything
docker exec -it booking-kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic booking-events.DLT --from-beginning --timeout-ms 5000
```

### See the Redis cache working

```bash
# first call = cache MISS (look for "Availability cache MISS" in the app log), later calls = HIT
for i in 1 2 3; do curl -s -o /dev/null -w "call $i: %{time_total}s\n" $API/resources/1/slots/available; done

docker exec -it booking-redis redis-cli KEYS '*'
docker exec -it booking-redis redis-cli TTL 'availability::1'      # counts down from 60
```

### Slot holds ("reserved while paying")

```bash
curl -s -X POST $API/holds -H "$JSON" -H "Authorization: Bearer $ALICE" -d '{"slotId":1}'   # Alice holds slot 1
curl -s $API/resources/1/slots/available                                                      # "held": true
curl -s -X POST $API/holds -H "$JSON" -H "Authorization: Bearer $BOB" -d '{"slotId":1}'     # 409
curl -s -X POST $API/bookings -H "$JSON" -H "Authorization: Bearer $BOB" -d '{"slotId":1}'  # 409 (held)
curl -s -X DELETE $API/holds/1 -H "Authorization: Bearer $ALICE" -i                          # release
```

### Rate limiting

```bash
for i in $(seq 1 12); do
  curl -s -o /dev/null -w "attempt $i: %{http_code}\n" -X POST $API/auth/login -H "$JSON" \
    -d '{"email":"nobody@example.com","password":"wrong-password"}'
done      # first 10 -> 401, then 429 with Retry-After
```

## Endpoints

| Method | Path | Who | Purpose |
|---|---|---|---|
| POST | `/api/auth/register` | public | create a USER account |
| POST | `/api/auth/login` | public, rate limited | get a JWT |
| GET | `/api/me` | logged in | current user |
| GET | `/api/resources` | public | list resources |
| GET | `/api/resources/{id}/slots/available` | public | free slots (cached) + `held` flag |
| POST | `/api/resources` | ADMIN | create resource |
| POST | `/api/resources/{id}/slots` | ADMIN | add slot |
| POST | `/api/holds` | logged in | hold a slot for 10 minutes (409 if held/booked) |
| DELETE | `/api/holds/{slotId}` | holder | release your hold |
| POST | `/api/bookings` | logged in, rate limited | book a slot (409 if taken or held by someone else) |
| GET | `/api/bookings/mine` | logged in | my bookings |
| POST | `/api/bookings/{id}/cancel` | owner or ADMIN | cancel (403 for others), idempotent |
| GET | `/api/notifications/mine` | logged in | my notifications (created from Kafka events) |

Status codes: `400` bad input, `401` not logged in / bad credentials, `403` not allowed, `404` not found,
`409` conflict, `429` too many requests, `503` holds temporarily unavailable (Redis down).

## How an event flows

```
book()  --one DB transaction-->  bookings row  +  outbox_events row
                                        |
        OutboxPublisher (every 1 s) reads unpublished rows, sends to Kafka, marks them published
                                        |
                              Kafka topic "booking-events"   (key = booking id, 3 partitions)
                                        |
        BookingEventConsumer --> NotificationService: insert notification (ON CONFLICT DO NOTHING) + "send email"
                                        |  fails 3 times? -> topic "booking-events.DLT"
```

## Project layout

```
docker-compose.yml                  PostgreSQL + Redis + Kafka (KRaft)
pom.xml                             dependencies
src/main/resources/
  application.yml                   DB, Redis, Kafka, JWT, holds, rate limits, outbox
  db/migration/V1__init.sql         resources, slots, bookings, double-booking index
  db/migration/V2__users_and_auth.sql   users table, bookings.user_id
  db/migration/V3__outbox_and_notifications.sql   outbox_events, notifications
src/main/java/com/example/booking/
  Resource, Slot, Booking           entities
  *Repository                       database access
  BookingService                    booking / cancel logic (holds, cache eviction, outbox events)
  ResourceController, BookingController   REST endpoints
  user/          AppUser, Role, UserRepository
  auth/          SecurityConfig, JwtService, AuthService, AuthController, AuthDtos, AdminSeeder
  cache/         CacheConfig, AvailabilityService (@Cacheable), AvailabilityCache (eviction)
  hold/          HoldService (Redis keys with TTL), HoldController
  ratelimit/     RateLimiter (Redis counter), RateLimitInterceptor, WebConfig
  events/        BookingEvent, OutboxEvent, OutboxRepository, OutboxService (write),
                 OutboxPublisher (relay to Kafka), KafkaConfig (topics, retries, DLT), BookingEventConsumer
  notification/  Notification, NotificationRepository, NotificationService (idempotent),
                 EmailSender (logs), NotificationController
src/test/java/com/example/booking/
  IntegrationTestBase               shared PostgreSQL + Redis + Kafka containers
  DoubleBookingTest, AuthFlowTest, RedisFeaturesTest, RateLimitHttpTest, KafkaOutboxTest
src/test/resources/application.properties   raises rate limits, slows the outbox relay for tests
```

## Security and reliability notes

- Passwords are stored as BCrypt hashes only. Set `JWT_SECRET` (32+ chars) in any real environment.
- Tokens last 1 hour and are not revocable yet.
- Rate limiting uses the client IP; behind a reverse proxy configure forwarded headers properly.
- Redis failures are logged and tolerated. Kafka failures never fail a booking: events wait in the outbox table.
- Delivery is at-least-once, so the consumer is idempotent (unique `event_id`).
- Published outbox rows are never deleted yet (see `MASTER.md` exercises).