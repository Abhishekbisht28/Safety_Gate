package com.makunai.safetygate.util;

import java.time.Duration;
import java.util.Map;

/**
 * Fetches a fresh access token via the /token/refresh/ endpoint using a
 * long-lived refresh token stored as a CI secret (DJANGO_REFRESH_TOKEN).
 *
 * Deliberately does NOT call the login endpoint on every run: Django flags
 * repeated automated logins and can mark the service account credentials as
 * unusable. Refreshing an existing token avoids that entirely.
 */
public final class JwtFetcher {

    private final String refreshUrl;
    private final String refreshToken;

    public JwtFetcher(String baseUrl, String refreshToken) {
        this.refreshUrl = stripTrailingSlash(baseUrl) + "/token/refresh/";
        this.refreshToken = refreshToken;
    }

    public String fetchAccessToken() throws Exception {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new IllegalStateException(
                    "DJANGO_REFRESH_TOKEN is not set. Store a long-lived refresh token as a CI secret " +
                    "instead of re-authenticating on every run.");
        }

        String payload = Json.write(Map.of("refresh", refreshToken));
        HttpUtil.TimedResponse resp = HttpUtil.request(
                "POST", refreshUrl, null, payload, Duration.ofSeconds(10));

        if (resp.statusCode != 200) {
            throw new IllegalStateException(
                    "Token refresh failed with status " + resp.statusCode + ": " + resp.body +
                    ". The refresh token may have expired and needs to be regenerated manually.");
        }

        Map<String, Object> parsed = Json.parseObject(resp.body);
        Object access = parsed.get("access");
        if (access == null) {
            throw new IllegalStateException("Refresh response did not contain an 'access' field: " + resp.body);
        }
        return access.toString();
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
