# kyc-account-services

Two Spring Boot 3.3 / Java 21 apps in one Maven project. They talk **only over Kafka**.

| Module | Port | Database | Owns |
|---|---|---|---|
| `account-creation-service` | 8081 | `kyc_creation` | applications (applicant + nominee + guarantor), KYC state machine, outbox relay, ack/verdict consumers, SLA sweep |
| `account-verification-service` | 8082 | `kyc_verification` | identity checks (bounded pool, one deadline), review record, delayed verdict poller, outbox relay, read-only review lookup |

Flow: `POST /api/v1/accounts` → `STARTED` (response returns immediately) → outbox relay publishes `kyc.request.v1` →
`IN_PROGRESS` → verification checks 3 identities in parallel → `kyc.ack.v1` → `MANUAL_REVIEW_PENDING` → after the
review delay `kyc.verdict.v1` → `KYC_DONE` / `REVIEW_FAILED`. About 5% of applications deliberately get no verdict;
the SLA sweep flags them (`slaBreachDetectedAt`, WARN log, `kyc.sla.breached` counter).

Contracts: `contracts/` (OpenAPI x3, AsyncAPI x1).

## Build and test

```bash
mvn -B test        # both modules; no Docker needed (embedded PostgreSQL 16 + Kafka KRaft, WireMock)
```

## Run the stack

```bash
docker compose up --build -d
curl -s localhost:8081/actuator/health ; curl -s localhost:8082/actuator/health
```

Compress the delays for a hands-on run (ISO-8601 durations, applied by environment):

```bash
KYC_REVIEW_DELAY_MIN=PT5S KYC_REVIEW_DELAY_MAX=PT10S KYC_SLA_WINDOW=PT60S KYC_SLA_SWEEP_INTERVAL=PT5S docker compose up --build -d
```

## Exercise the APIs

Aadhaar numbers must pass the Verhoeff checksum; `234567890124`-style values do not necessarily. Use e.g. `499118665246`
(valid checksum) for every person, with a different PAN per person.

```bash
curl -s -X POST localhost:8081/api/v1/accounts -H 'Content-Type: application/json' -d '{
 "primary":  {"name":"Asha Rao","address":"1 MG Rd","pan":"ABCDE1234F","aadhaar":"499118665246","parentsName":"Ravi Rao","annualIncome":1200000,"incomeSource":"Salary"},
 "nominee":  {"name":"Ravi Rao","address":"1 MG Rd","pan":"BCDEF2345G","aadhaar":"499118665246","parentsName":"Sita Rao","annualIncome":500000,"incomeSource":"Pension"},
 "guarantor":{"name":"Meera Rao","address":"2 MG Rd","pan":"CDEFG3456H","aadhaar":"499118665246","parentsName":"Hari Rao","annualIncome":900000,"incomeSource":"Business"}}'
curl -s localhost:8081/api/v1/accounts/<applicationId>          # poll the KYC state
curl -s 'localhost:8081/api/v1/accounts?slaBreached=true'
curl -s localhost:8082/api/v1/reviews/<applicationId>            # verification-side review record
```

**Reserved mismatch PAN:** give any person the PAN `ZZZZZ9999Z`. The compose fake identity provider reports a mismatch
for it, so the application ends `REVIEW_FAILED` with `Identity mismatch for <ROLE>: Reserved test PAN: simulated mismatch`.
Every other PAN matches.

## Watch the Kafka round trip

```bash
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic kyc.request.v1 --from-beginning --property print.key=true
# likewise kyc.ack.v1, kyc.verdict.v1, and the dead-letter topics *-dlt
```

## Replay a dead-lettered message by hand

There is no replay tooling. To replay one record from `kyc.request.v1-dlt`:

```bash
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic kyc.request.v1-dlt --from-beginning --property print.key=true --timeout-ms 5000 > dlt.txt
# copy the wanted "key<TAB>value" line, then re-publish it to the main topic:
docker compose exec -i kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 \
  --topic kyc.request.v1 --property parse.key=true --property key.separator=$'\t' < line.txt
```
(Same for `kyc.ack.v1-dlt` / `kyc.verdict.v1-dlt`.) Replays are safe: consumers dedupe on (applicationId, message type).

## Observability

Actuator `/actuator/metrics/<name>`: `kyc.sla.breached`, `kyc.dlt.messages{topic}`, `kyc.outbox.oldest.age.seconds`,
`kyc.message.duplicate{type}`, `kyc.transition.rejected{from,to}`, `kyc.verdict.outcome{outcome}`,
`kyc.identity.call{result}`, `kyc.identity.abandoned{reason}`, `executor.active{name=identityCheckExecutor}`.

## Reset

`docker compose down -v` (drops the Postgres volume; Flyway rebuilds both schemas on the next start).
