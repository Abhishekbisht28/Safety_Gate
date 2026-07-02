package com.makunai.safetygate.util;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Thin wrapper around java.net.http.HttpClient so every stage issues
 * requests the same way (timeouts, headers, timing capture).
 */
public final class HttpUtil {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private HttpUtil() {
    }

    public static final class TimedResponse {
        public final int statusCode;
        public final String body;
        public final long durationMillis;
        public final Map<String, java.util.List<String>> headers;

        TimedResponse(int statusCode, String body, long durationMillis,
                      Map<String, java.util.List<String>> headers) {
            this.statusCode = statusCode;
            this.body = body;
            this.durationMillis = durationMillis;
            this.headers = headers;
        }
    }

    public static TimedResponse request(String method, String url, String bearerToken,
                                         String jsonBody, Duration timeout) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(timeout == null ? Duration.ofSeconds(15) : timeout)
                .header("Accept", "application/json")
                .header("User-Agent", "PostmanRuntime/7.36.0"); // avoids Django bot-flagging on auth endpoints

        if (bearerToken != null && !bearerToken.isBlank()) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }

        HttpRequest.BodyPublisher publisher = (jsonBody == null)
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(jsonBody);

        if (jsonBody != null) {
            builder.header("Content-Type", "application/json");
        }

        switch (method.toUpperCase()) {
            case "GET": builder.GET(); break;
            case "POST": builder.POST(publisher); break;
            case "PUT": builder.PUT(publisher); break;
            case "DELETE": builder.DELETE(); break;
            case "PATCH": builder.method("PATCH", publisher); break;
            default: throw new IllegalArgumentException("Unsupported method: " + method);
        }

        long start = System.nanoTime();
        HttpResponse<String> response = CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        return new TimedResponse(response.statusCode(), response.body(), elapsedMs, response.headers().map());
    }
}
