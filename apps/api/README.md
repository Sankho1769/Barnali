# BARNALI API

Spring Boot 4 REST API targeting Java 21. It provides session authentication, public community reads, member mutations, admin services, PostgreSQL JDBC, and Flyway migrations. See `../../docs/API.md`, `DATABASE.md`, `SECURITY.md`, and `DEVELOPMENT.md`.

Configure `.env` from the workspace root, then run `scripts/start-api.ps1` or execute `mvn spring-boot:run` from this folder. A reachable PostgreSQL database and `APP_SECURITY_HMAC_KEY` are required. `mvn test` runs unit tests; it does not verify the database migrations or API integration.
