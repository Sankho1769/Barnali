# Workspace scripts

`start-api.ps1` loads variables from the untracked workspace `.env` into the process and launches Spring Boot with Maven. Do not place secrets on the command line, echo them, or commit the environment file. The script and database startup were not run against a live database in this workspace.
# Windows API scripts

`start-api.ps1` reads `.env` into the child PowerShell process and starts Spring Boot. Spring Boot has Flyway enabled, so this is an application-start command and can apply pending migrations.

`diagnose-database.ps1` is a separate, non-application diagnostic. It compiles the API sources, reports only environment-variable presence and sanitized JDBC endpoint details, then runs `Resolve-DnsName` and `Test-NetConnection` in the invoking Windows PowerShell environment. It never starts Spring Boot or Flyway.

Run a configuration and network check without database authentication:

```powershell
.\scripts\diagnose-database.ps1
```

Opt in to a PostgreSQL authentication check that uses the loaded `DATABASE_USERNAME` and `DATABASE_PASSWORD`, requires an encrypted SSL mode, executes only `SELECT 1`, and closes the connection. The check refuses to connect when the URL embeds credentials, has an ambiguous fragment, or does not require SSL:

```powershell
.\scripts\diagnose-database.ps1 -TestConnection
```

The environment loader splits each non-comment line at its first `=` and treats the remainder as literal data. Characters such as `&`, `#`, `%`, `+`, and additional `=` characters are preserved; inline comments and shell expansions are not interpreted. Quotes are preserved literally, so `.env` values should not be wrapped in shell quotes. The diagnostic never displays credential values, HMAC values, exception messages, or the full JDBC URL.

Run the PowerShell loader regression check with:

```powershell
.\scripts\tests\Verify-EnvironmentLoader.ps1
```
