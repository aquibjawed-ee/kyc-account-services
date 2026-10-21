# kyc-account-services

Multi-module Maven project (Java 21, Spring Boot 3.3): `account-creation-service` (8081) and
`account-verification-service` (8082). They communicate only over Kafka. See `README.md`.

## Build & Test

```bash
mvn -B test
```

Integration tests run PostgreSQL 16 (Zonky embedded) and Kafka (spring-kafka-test, KRaft) in-JVM; no Docker
daemon is needed. The first build downloads the embedded PostgreSQL binaries from Maven Central.
