# Booking System (Work In Progress)

A booking backend built with **Spring Boot, PostgreSQL, Flyway and JWT authentication**.
So far it has:

1. **A slot can never be booked twice**, even when many people click at the same moment.
2. **Only logged-in users can book**, only admins can create resources, and you can only cancel your own bookings.

## Setup 

This project is done using Docker Desktop on Windows and WSL.

```bash
sudo apt update
sdk install java 21
sdk install maven
sudo snap install jq
```

Ensure you check every version before starting!

## Run

```bash
docker compose up -d        
mvn spring-boot:run        
```

## Test

```bash
mvn test   
```

## Endpoints

| Method | Path | Who | Purpose |
|---|---|---|---|
| POST | `/api/auth/register` | public | create a USER account |
| POST | `/api/auth/login` | public | get a JWT |
| GET | `/api/me` | logged in | current user |
| GET | `/api/resources` | public | list resources |
| GET | `/api/resources/{id}/slots/available` | public | free slots |
| POST | `/api/resources` | ADMIN | create resource |
| POST | `/api/resources/{id}/slots` | ADMIN | add slot |
| POST | `/api/bookings` | logged in | book a slot (409 if taken) |
| GET | `/api/bookings/mine` | logged in | my bookings |
| POST | `/api/bookings/{id}/cancel` | owner or ADMIN | cancel (403 for others) |

Status codes: `400` bad input, `401` not logged in / bad credentials, `403` not allowed, `404` not found, `409` conflict.

