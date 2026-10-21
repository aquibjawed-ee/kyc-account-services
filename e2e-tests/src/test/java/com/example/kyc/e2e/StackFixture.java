package com.example.kyc.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Random;
import java.util.UUID;

/**
 * Talks to the two deployed services over plain HTTP. No Spring context, no service classes on the
 * classpath: these tests must not be able to pass by accident against an in-JVM stand-in.
 */
final class StackFixture {

    static final String CREATION = System.getProperty("e2e.creation.url", "http://localhost:8081");
    static final String VERIFICATION = System.getProperty("e2e.verification.url", "http://localhost:8082");

    /** The compose fake identity provider reports a mismatch for exactly this PAN. */
    static final String RESERVED_MISMATCH_PAN = "ZZZZZ9999Z";

    /** An Aadhaar with a valid Verhoeff checksum; the services reject anything that fails it. */
    static final String VALID_AADHAAR = "499118665246";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).build();
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Random RANDOM = new Random();

    private StackFixture() {}

    record Response(int status, JsonNode body) {
        JsonNode at(String field) {
            return body == null ? null : body.get(field);
        }

        String text(String field) {
            JsonNode n = at(field);
            return n == null || n.isNull() ? null : n.asText();
        }
    }

    /** True when both services answer their health endpoint with UP. */
    static boolean stackIsUp() {
        try {
            return "UP".equals(get(CREATION + "/actuator/health").text("status"))
                    && "UP".equals(get(VERIFICATION + "/actuator/health").text("status"));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * True when the stack runs compressed timings, i.e. a verdict is due soon enough to wait for.
     * Determined from a real review record rather than from configuration we cannot see, so the
     * tests adapt to whichever profile the operator started.
     */
    static boolean verdictsArriveQuickly() {
        try {
            String id = createApplication().text("applicationId");
            JsonNode due = awaitReview(UUID.fromString(id)).at("verdictDueAt");
            if (due == null || due.isNull()) return false;
            return Duration.between(Instant.now(), Instant.parse(due.asText())).toSeconds() < 120;
        } catch (RuntimeException e) {
            return false;
        }
    }

    static String randomPan() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5; i++) sb.append((char) ('A' + RANDOM.nextInt(26)));
        for (int i = 0; i < 4; i++) sb.append(RANDOM.nextInt(10));
        return sb.append((char) ('A' + RANDOM.nextInt(26))).toString();
    }

    static String person(String name, String pan) {
        return person(name, pan, VALID_AADHAAR);
    }

    static String person(String name, String pan, String aadhaar) {
        return """
                {"name":"%s","address":"1 MG Rd","pan":"%s","aadhaar":"%s",\
                "parentsName":"Parent %s","annualIncome":900000,"incomeSource":"Salary"}"""
                .formatted(name, pan, aadhaar, name);
    }

    /** A valid application with three distinct random PANs. */
    static Response createApplication() {
        return createApplication(randomPan(), randomPan(), randomPan());
    }

    static Response createApplication(String primaryPan, String nomineePan, String guarantorPan) {
        return post(CREATION + "/api/v1/accounts", """
                {"primary":%s,"nominee":%s,"guarantor":%s}""".formatted(
                person("Asha", primaryPan), person("Ravi", nomineePan), person("Meera", guarantorPan)));
    }

    static Response application(UUID id) {
        return get(CREATION + "/api/v1/accounts/" + id);
    }

    static String statusOf(UUID id) {
        return application(id).text("kycStatus");
    }

    static Response review(UUID id) {
        return get(VERIFICATION + "/api/v1/reviews/" + id);
    }

    /** Waits for the verification service to have consumed the request and created its review. */
    static Response awaitReview(UUID id) {
        return awaitUntil(() -> review(id), r -> r.status() == 200, Duration.ofSeconds(30),
                "review record to appear on the verification service for " + id);
    }

    static Response get(String url) {
        return send(HttpRequest.newBuilder(URI.create(url)).GET());
    }

    /** Status plus the body as text. For endpoints that do not return JSON, such as Swagger UI. */
    static RawResponse getRaw(String url) {
        try {
            HttpResponse<String> res = HTTP.send(
                    HttpRequest.newBuilder(URI.create(url)).GET().timeout(Duration.ofSeconds(10)).build(),
                    HttpResponse.BodyHandlers.ofString());
            return new RawResponse(res.statusCode(), res.body() == null ? "" : res.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        } catch (Exception e) {
            throw new IllegalStateException("request failed: " + e.getMessage(), e);
        }
    }

    record RawResponse(int status, String body) {}

    static Response post(String url, String json) {
        return send(HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)));
    }

    static Response patch(String url, String json) {
        return send(HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(json)));
    }

    static Response delete(String url) {
        return send(HttpRequest.newBuilder(URI.create(url)).DELETE());
    }

    private static Response send(HttpRequest.Builder builder) {
        try {
            HttpResponse<String> res = HTTP.send(builder.timeout(Duration.ofSeconds(10)).build(),
                    HttpResponse.BodyHandlers.ofString());
            String body = res.body();
            return new Response(res.statusCode(),
                    body == null || body.isBlank() ? null : MAPPER.readTree(body));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        } catch (Exception e) {
            throw new IllegalStateException("request failed: " + e.getMessage(), e);
        }
    }

    /** Polls until the predicate holds, then returns the value; fails with `what` on timeout. */
    static <T> T awaitUntil(java.util.function.Supplier<T> call, java.util.function.Predicate<T> done,
                            Duration timeout, String what) {
        Instant deadline = Instant.now().plus(timeout);
        T last = null;
        while (Instant.now().isBefore(deadline)) {
            try {
                last = call.get();
                if (done.test(last)) return last;
            } catch (RuntimeException ignored) {
                // the stack may still be settling; keep polling until the deadline
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted", e);
            }
        }
        throw new AssertionError("Timed out after " + timeout.toSeconds() + "s waiting for " + what
                + ". Last observed: " + last);
    }
}
