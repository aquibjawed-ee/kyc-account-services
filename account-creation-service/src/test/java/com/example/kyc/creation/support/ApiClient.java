package com.example.kyc.creation.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/** JDK HttpClient wrapper (TestRestTemplate cannot send PATCH without extra dependencies). */
public class ApiClient {

    public record Resp(int status, JsonNode body, String raw, String location) {}

    private final HttpClient http = HttpClient.newHttpClient();
    private final int port;
    private final ObjectMapper mapper;

    public ApiClient(int port, ObjectMapper mapper) {
        this.port = port;
        this.mapper = mapper;
    }

    public Resp get(String path) { return send("GET", path, null); }

    public Resp post(String path, JsonNode body) { return send("POST", path, body); }

    public Resp patch(String path, JsonNode body) { return send("PATCH", path, body); }

    public Resp delete(String path) { return send("DELETE", path, null); }

    public Resp postRaw(String path, String rawBody) { return sendRaw("POST", path, rawBody); }

    private Resp send(String method, String path, JsonNode body) {
        try {
            return sendRaw(method, path, body == null ? null : mapper.writeValueAsString(body));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Resp sendRaw(String method, String path, String rawBody) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .header("Content-Type", "application/json").header("Accept", "application/json, application/problem+json");
            b.method(method, rawBody == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(rawBody));
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode json = r.body() == null || r.body().isBlank() ? null : mapper.readTree(r.body());
            return new Resp(r.statusCode(), json, r.body(), r.headers().firstValue("Location").orElse(null));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
