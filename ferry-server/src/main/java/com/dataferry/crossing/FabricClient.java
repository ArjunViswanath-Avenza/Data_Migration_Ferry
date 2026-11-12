package com.dataferry.crossing;

import com.dataferry.config.ConfigModel.TargetEnvironment;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;

/**
 * Talks to the vendor's Fabric app: logs in with the app key and secret (read from the environment
 * variables the environment names, never from a file), keeps the claims token, renews it before it
 * expires and once more if a call says it has expired.
 *
 * Every call ends in one of four kinds of result, because each needs a different response:
 * a body to judge, a refusal to retry (429, 5xx), a call that was never delivered (safe to retry),
 * or a call whose outcome is unknown (timed out after sending: never retried blindly).
 */
final class FabricClient {

    enum Kind { BODY, RETRYABLE, NOT_SENT, UNKNOWN }

    record Response(Kind kind, int httpStatus, Map<String, Object> body, String text, long millis) {}

    private final HttpClient http;
    private final ObjectMapper om;
    private final TargetEnvironment env;
    private final String basePath;
    private final String appKey, appSecret;
    private final Duration timeout;
    private final int renewBeforeSeconds, defaultLifetimeSeconds;
    private String token;
    private long tokenExpiresAt;      // epoch millis

    FabricClient(TargetEnvironment env, String basePath, String appKey, String appSecret, CrossingConfig cfg, ObjectMapper om) {
        this.env = env;
        this.basePath = basePath == null ? "" : basePath;
        this.appKey = appKey;
        this.appSecret = appSecret;
        this.om = om;
        this.timeout = Duration.ofSeconds(Math.max(1, cfg.requestTimeoutSeconds));
        this.renewBeforeSeconds = cfg.tokenRenewBeforeSeconds;
        this.defaultLifetimeSeconds = cfg.defaultTokenLifetimeSeconds;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    synchronized void login() {
        HttpRequest req = HttpRequest.newBuilder(URI.create(env.authUrl)).timeout(timeout)
                .header("X-Kony-App-Key", appKey).header("X-Kony-App-Secret", appSecret)
                .header("Content-Type", "application/json").header("X-Kony-Reportingparams", "\"\"")
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        try {
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) throw new IllegalStateException("Sign-in to " + env.label + " was refused (HTTP " + r.statusCode() + ").");
            Map<?, ?> body = om.readValue(r.body(), Map.class);
            Map<?, ?> claims = (Map<?, ?>) body.get("claims_token");
            if (claims == null || claims.get("value") == null) throw new IllegalStateException("Sign-in answer from " + env.label + " has no claims_token.");
            token = String.valueOf(claims.get("value"));
            long exp = claims.get("exp") instanceof Number n ? n.longValue() : 0;
            if (exp > 0 && exp < 10_000_000_000L) exp *= 1000;      // seconds or millis
            tokenExpiresAt = exp > System.currentTimeMillis() ? exp : System.currentTimeMillis() + defaultLifetimeSeconds * 1000L;
        } catch (IOException e) {
            throw new IllegalStateException("Could not reach the sign-in address " + env.authUrl + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during sign-in");
        }
    }

    synchronized String currentToken() {
        if (token == null || System.currentTimeMillis() > tokenExpiresAt - renewBeforeSeconds * 1000L) login();
        return token;
    }

    private synchronized void dropToken(String old) { if (old != null && old.equals(token)) token = null; }

    Response call(String path, Map<String, Object> payload) {
        Response r = send(path, payload, currentToken());
        if (r.kind() == Kind.BODY && r.httpStatus() == 401) {        // expired early: renew once
            dropToken(null);
            synchronized (this) { token = null; }
            r = send(path, payload, currentToken());
        }
        return r;
    }

    private Response send(String path, Map<String, Object> payload, String tok) {
        long t0 = System.currentTimeMillis();
        String text;
        try {
            text = om.writeValueAsString(payload);
        } catch (IOException e) {
            throw new IllegalStateException("The request could not be written as JSON: " + e.getMessage());
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(env.hostUrl + basePath + path)).timeout(timeout)
                .header("X-Kony-App-Key", appKey).header("X-Kony-App-Secret", appSecret)
                .header("X-Kony-Authorization", tok).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(text)).build();
        try {
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            long ms = System.currentTimeMillis() - t0;
            Map<String, Object> body = parse(r.body());
            int sc = r.statusCode();
            if (sc == 429 || sc == 502 || sc == 503 || sc == 504 || sc == 500) return new Response(Kind.RETRYABLE, sc, body, r.body(), ms);
            return new Response(Kind.BODY, sc, body, r.body(), ms);
        } catch (HttpTimeoutException e) {
            return new Response(Kind.UNKNOWN, 0, Map.of(), "No answer within " + timeout.toSeconds() + " s", System.currentTimeMillis() - t0);
        } catch (ConnectException e) {
            return new Response(Kind.NOT_SENT, 0, Map.of(), "Could not connect: " + e.getMessage(), System.currentTimeMillis() - t0);
        } catch (IOException e) {
            return new Response(Kind.UNKNOWN, 0, Map.of(), "Connection broke: " + e.getMessage(), System.currentTimeMillis() - t0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Response(Kind.UNKNOWN, 0, Map.of(), "Interrupted", System.currentTimeMillis() - t0);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String s) {
        try { return s == null || s.isBlank() ? Map.of() : om.readValue(s, Map.class); }
        catch (IOException e) { return Map.of("_unparsed", s.length() > 500 ? s.substring(0, 500) : s); }
    }
}
