# LIYU LOTTERY

Telegram-first lottery management for English, Amharic, and Tigrigna customers. The project includes Spring Boot configuration, MariaDB/Flyway schema, operational health checks, Telegram long polling, user registration and language selection, localized menus, database-backed campaign and prize administration, and an initial manual payment review workflow.

## Safety and business status

Real-money sales are disabled by default (`PAYMENTS_ENABLED=false`). Keep them disabled until the owner has verified applicable legal authorization, official rules, approved payment details, and customer support/privacy information. This project does not claim that LIYU LOTTERY is licensed. No real payment details, bot credentials, or customer information belong in this repository.

## Stack

- Java 25, Maven, Spring Boot 4.1.1
- MariaDB 11.8, Flyway migrations, Spring Data JPA
- Spring Security, Bean Validation, Actuator
- Telegram integration via the official HTTP Bot API
- JUnit 5 and Testcontainers dependencies

The Telegram integration uses long polling and persists received updates before processing them. Run one application instance per bot token; do not run competing pollers.

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

To connect a bot, create it with Telegram's official [BotFather](https://core.telegram.org/bots/features#botfather), then put its token directly into the private `.env` file and set `TELEGRAM_ENABLED=true`. The current Telegram username remains configurable as `TELEGRAM_BOT_USERNAME` and defaults to `AmaziaLotteryBot`. Never put the token in a command, source file, issue, or chat message. Webhooks are not implemented.

The first super-admin is added by an authorized database operator after independently confirming the owner's Telegram numeric ID. Run these statements using a restricted DBA session, replacing the placeholder with that verified ID:

```sql
INSERT INTO admins(telegram_user_id, active) VALUES (<VERIFIED_OWNER_TELEGRAM_ID>, TRUE);
INSERT INTO admin_roles(admin_id, role_name)
SELECT id, 'SUPER_ADMIN' FROM admins WHERE telegram_user_id = <VERIFIED_OWNER_TELEGRAM_ID>;
```

Do not expose a SQL console to the bot or customers. `INITIAL_SUPER_ADMIN_ID` is reserved for a future reviewed bootstrap flow; it currently has no effect.

Payment methods also require explicit operator configuration. Keep `PAYMENTS_ENABLED=false` until the owner has verified legal requirements, approved the method details, official campaign rules, and support/privacy information. With a restricted DBA session, insert only the approved payment method and its reviewed translations into `payment_instructions` and `payment_instruction_translations`; no payment method or account details are supplied by this project. Customer orders remain blocked until both the feature flag and at least one active payment instruction exist.

Customers can request a price quote with `/buy`, send `/order CAMPAIGN_ID QUANTITY`, review the quote, and send `/confirm`. After payment, they attach an image or PDF with caption `/receipt ORDER_REFERENCE`. A payment reviewer can use `/pendingpayments` to retrieve pending receipts, then `/paymentapprove ORDER_REFERENCE VERIFIED_TRANSACTION_REFERENCE [note]` or `/paymentreject ORDER_REFERENCE reason`. The reviewer must verify the transaction against the official account independently; a receipt image is not evidence of payment. Approval marks the order payment verified but does not issue tickets yet. Receipt file IDs are retained in MariaDB while the file itself remains hosted by Telegram; configure a separate private retention and backup policy before production use.

Flyway applies versioned schema migrations at startup. Hibernate is configured with `ddl-auto: validate`; it does not create or modify the production schema.

## Configuration

See `.env.example` for placeholder variables. `TELEGRAM_BOT_USERNAME` defaults to the existing `AmaziaLotteryBot`; changing LIYU branding does not change a Telegram username. `INITIAL_SUPER_ADMIN_ID` is reserved for the secure admin bootstrap in a later phase. Never send bot tokens or passwords in chat or commit them.

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

- Phase 1: Maven test build passes; migration execution against MariaDB is not yet verified.
- Phase 2: partial. Telegram long polling, update deduplication, profile persistence, language selection, `/start`, `/help`, `/language`, `/prizes`, `/tickets`, `/results`, and the localized main menu are implemented. Ticket history and results await later phases.
- Phase 3: campaign and prize creation, translations, lifecycle commands, auditing, and customer campaign/prize information are implemented. MariaDB migration execution and end-to-end Telegram use still need environment verification.
- Phase 4: partial. Quote and confirmation flow, server-side price calculation, order price snapshots, receipt image/PDF submissions, and role-checked manual payment decisions are implemented. Payment instructions must be configured in the database. Reviewers retrieve receipts with `/pendingpayments`; automatic reviewer notifications, replacement-receipt requests, and MariaDB integration tests remain outstanding.
- Phases 5–7: not implemented yet. Approved payments do not issue tickets. Customer ticket history, result publication, reports, operational hardening, and production verification remain outstanding.

Amharic and Tigrigna starter interface translations are distinct. Have native speakers review them before customer release. Legal terms, official rules, privacy terms, and payment instructions remain unconfigured and must receive business and legal review; the application does not invent them.

The Git working tree is on `main`. Commits are local; no push is claimed.
