# API tests

JUnit/Spring test sources are under `java/com/barnali/api`. `mvn test` currently runs nine unit tests covering owner permission gates, role decisions, password policy, selected community mutation logic, and production configuration validation. There are no database migration or HTTP authentication integration fixtures yet.
