# LIYU LOTTERY

Telegram-first lottery management for English, Amharic, and Tigrigna customers. This repository currently contains the Phase 1 foundation: Spring Boot configuration, MariaDB/Flyway schema, operational health checks, and container scaffolding. Telegram workflows and production business operations are not yet implemented.

## Safety and business status

Real-money sales are disabled by default (`PAYMENTS_ENABLED=false`). Keep them disabled until the owner has verified applicable legal authorization, official rules, approved payment details, and customer support/privacy information. This project does not claim that LIYU LOTTERY is licensed. No real payment details, bot credentials, or customer information belong in this repository.

## Stack

- Java 25, Maven, Spring Boot 4.1.1
- MariaDB 11.8, Flyway migrations, Spring Data JPA
- Spring Security, Bean Validation, Actuator
- Telegram Bot API library dependency is declared; bot processing is a later phase
- JUnit 5 and Testcontainers dependencies

Spring Boot 4.1.1 officially supports Java 25; see [Spring Boot system requirements](https://docs.spring.io/spring-boot/system-requirements.html).

## Prerequisites

Install JDK 25, Maven 3.6.3+, and Docker with Compose. For a local build, Maven must be able to access Maven Central. A Telegram bot token is not needed to build the foundation.

## Local setup

```bash
cp .env.example .env
```

Replace the sample database passwords in the private `.env` file before starting Compose. Keep `PAYMENTS_ENABLED=false` and `TELEGRAM_ENABLED=false` during development. `.env` is ignored by Git.

```bash
docker compose up -d database
mvn clean verify
```

The application container can be built and launched after the Maven package has been produced:

```bash
docker compose up --build -d
curl http://localhost:8080/actuator/health
```

MariaDB is not published on a host port by Compose. The app is bound to `127.0.0.1:8080` for local use. In production, terminate TLS at a trusted reverse proxy and restrict access to operational endpoints.

Flyway applies versioned schema migrations at startup. Hibernate is configured with `ddl-auto: validate`; it does not create or modify the production schema.

## Configuration

See `.env.example` for placeholder variables. `TELEGRAM_BOT_USERNAME` defaults to the existing `AmaziaLotteryBot`; changing LIYU branding does not change a Telegram username. `INITIAL_SUPER_ADMIN_ID` is reserved for the secure admin bootstrap in the Telegram phase. Never send bot tokens or passwords in chat or commit them.

## Database backup and restore

Back up the MariaDB volume using a database-aware logical dump and encrypt/store it outside the application host. Example when running MariaDB locally:

```bash
mariadb-dump --single-transaction -h 127.0.0.1 -u "$DB_USER" -p "$DB_NAME" > backup.sql
mariadb -h DB_HOST -u DB_USER -p DB_NAME < backup.sql
```

Restrict backup access, test restores regularly, and define retention to meet applicable privacy and business requirements. Telegram file IDs are not a receipt backup strategy; a later phase must define private receipt storage and retention.

## Project layout

```text
src/main/java/com/liyulottery/   Application and configuration
src/main/resources/db/migration/ Flyway schema
src/main/resources/i18n/         English, Amharic, and Tigrigna message bundles
```

## Implementation status

- Phase 1: in progress. Base configuration, Docker Compose, and initial normalized schema are present; build, migrations, and tests still need verification.
- Phases 2–7: not implemented yet. In particular, no customer or admin Telegram workflow is active, and payment approvals/tickets/results are not operational.

The Git working tree is on `main`. This environment did not expose the GitHub remote URL successfully, so no push or commit is claimed.
