package com.makunai.safetygate.security;

import com.makunai.safetygate.report.StageResult;
import com.makunai.safetygate.report.StageResult.Finding;
import com.makunai.safetygate.report.StageResult.Finding.Severity;
import com.makunai.safetygate.util.HttpUtil;
import com.makunai.safetygate.util.Json;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

/**
 * Stage 5 — Security Surface Scan.
 *
 * Scoped to endpoints changed by the PR. For each GET endpoint with query
 * parameters declared in apis.json, injects SQLi/XSS payloads into each
 * parameter and confirms the backend responds safely (no 500, no payload
 * reflected unescaped, no SQL error leakage). Also runs JWT-tampering probes
 * against any endpoint requiring auth, and an IDOR probe when apis.json
 * declares an "ownerParam" (e.g. manual_push_by should not accept an
 * arbitrary user id different from the caller's own token).
 */
public final class SecurityPayloadRunner {

    private final String baseUrl;
    private final String accessToken;

    public SecurityPayloadRunner(String baseUrl, String accessToken) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.accessToken = accessToken;
    }

    @SuppressWarnings("unchecked")
    public StageResult run(Path apisJsonPath, Set<String> scopedEndpoints) throws Exception {
        StageResult result = new StageResult("Security Surface Scan");
        long start = System.currentTimeMillis();

        Map<String, Object> apisRaw = Json.parseObject(Files.readString(apisJsonPath));
        List<Object> apis = (List<Object>) apisRaw.getOrDefault("apis", List.of());

        for (Object o : apis) {
            Map<String, Object> api = (Map<String, Object>) o;
            String method = api.get("method").toString();
            String path = api.get("path").toString();
            String key = method + " " + path;

            if (!scopedEndpoints.isEmpty() && !scopedEndpoints.contains(key)) {
                continue;
            }

            boolean requiresAuth = Boolean.TRUE.equals(api.get("requiresAuth"));
            List<Object> queryParams = (List<Object>) api.getOrDefault("queryParams", List.of());
            String ownerParam = api.containsKey("ownerParam") ? api.get("ownerParam").toString() : null;

            if ("GET".equalsIgnoreCase(method)) {
                for (Object qp : queryParams) {
                    runInjectionProbes(key, path, qp.toString(), requiresAuth, result);
                }
            }

            if (requiresAuth) {
                runJwtTamperProbes(key, method, path, result);
            }

            if (ownerParam != null) {
                runIdorProbe(key, method, path, ownerParam, result);
            }

            checkCsrfOnGet(key, method, path, requiresAuth, result);
        }

        result.durationMillis = System.currentTimeMillis() - start;
        return result;
    }

    private void runInjectionProbes(String key, String path, String param, boolean requiresAuth,
                                     StageResult result) throws Exception {
        List<String> allProbes = new ArrayList<>();
        allProbes.addAll(PayloadLibrary.sqlInjectionProbes());
        allProbes.addAll(PayloadLibrary.xssProbes());

        for (String payload : allProbes) {
            String encoded = URLEncoder.encode(payload, StandardCharsets.UTF_8);
            String url = baseUrl + path + (path.contains("?") ? "&" : "?") + param + "=" + encoded;

            HttpUtil.TimedResponse resp = HttpUtil.request(
                    "GET", url, requiresAuth ? accessToken : null, null, Duration.ofSeconds(10));

            if (resp.statusCode >= 500) {
                result.addFinding(new Finding(Severity.CRITICAL, key,
                        "Injection payload caused server error",
                        "param=" + param + " payload=" + payload + " -> HTTP " + resp.statusCode +
                        ". Likely unsanitized input reaching the DB/template layer."));
                continue;
            }
            if (resp.body != null && resp.body.contains(payload)) {
                result.addFinding(new Finding(Severity.HIGH, key,
                        "Payload reflected unescaped in response",
                        "param=" + param + " payload='" + payload + "' was echoed back verbatim — possible XSS."));
                continue;
            }
            if (resp.body != null && looksLikeSqlError(resp.body)) {
                result.addFinding(new Finding(Severity.CRITICAL, key,
                        "SQL error leaked in response body",
                        "param=" + param + " triggered a database error message — information disclosure."));
            }
        }
    }

    private void runJwtTamperProbes(String key, String method, String path, StageResult result) throws Exception {
        for (String badToken : PayloadLibrary.jwtTamperCases()) {
            HttpUtil.TimedResponse resp = HttpUtil.request(
                    method, baseUrl + path, badToken, null, Duration.ofSeconds(10));
            if (resp.statusCode != 401 && resp.statusCode != 403) {
                result.addFinding(new Finding(Severity.CRITICAL, key,
                        "Tampered/invalid JWT was accepted",
                        "Expected 401/403 but got " + resp.statusCode +
                        " for token starting with '" + badToken.substring(0, Math.min(20, badToken.length())) + "...'"));
            }
        }
        // Also confirm a request with no token at all is rejected.
        HttpUtil.TimedResponse noAuth = HttpUtil.request(method, baseUrl + path, null, null, Duration.ofSeconds(10));
        if (noAuth.statusCode != 401 && noAuth.statusCode != 403) {
            result.addFinding(new Finding(Severity.CRITICAL, key,
                    "Endpoint accessible without authentication",
                    "Expected 401/403 with no token but got " + noAuth.statusCode));
        }
    }

    private void runIdorProbe(String key, String method, String path, String ownerParam,
                               StageResult result) throws Exception {
        // Try accessing the resource with an owner id unlikely to belong to the test account (IDOR probe).
        String probedUrl = baseUrl + path + (path.contains("?") ? "&" : "?") + ownerParam + "=999999999";
        HttpUtil.TimedResponse resp = HttpUtil.request(method, probedUrl, accessToken, null, Duration.ofSeconds(10));
        if (resp.statusCode == 200) {
            result.addFinding(new Finding(Severity.CRITICAL, key,
                    "Possible IDOR on " + ownerParam,
                    "Request for a resource owned by a different user_id returned 200 instead of 403/404. " +
                    "Confirm the endpoint checks '" + ownerParam + "' against the token's own user_id."));
        } else {
            result.addFinding(new Finding(Severity.INFO, key,
                    ownerParam + " ownership check OK", "Cross-account access correctly rejected (" + resp.statusCode + ")."));
        }
    }

    private void checkCsrfOnGet(String key, String method, String path, boolean requiresAuth,
                                 StageResult result) throws Exception {
        if (!"GET".equalsIgnoreCase(method)) return;
        HttpUtil.TimedResponse resp = HttpUtil.request(
                "GET", baseUrl + path, requiresAuth ? accessToken : null, null, Duration.ofSeconds(10));
        boolean hasCsrfHeader = resp.headers.keySet().stream()
                .anyMatch(h -> h.toLowerCase().contains("csrf"));
        boolean bodyMentionsCsrf = resp.body != null && resp.body.toLowerCase().contains("csrf");
        if (hasCsrfHeader || bodyMentionsCsrf) {
            result.addFinding(new Finding(Severity.LOW, key,
                    "CSRF token present on a GET endpoint",
                    "GET requests are not state-changing and normally shouldn't require/return CSRF tokens. " +
                    "This usually indicates leftover middleware — verify it's intentional."));
        }
    }

    private boolean looksLikeSqlError(String body) {
        String lower = body.toLowerCase();
        return lower.contains("sql syntax") || lower.contains("psycopg2") || lower.contains("django.db.utils")
                || lower.contains("integrityerror") || lower.contains("operationalerror");
    }
}
