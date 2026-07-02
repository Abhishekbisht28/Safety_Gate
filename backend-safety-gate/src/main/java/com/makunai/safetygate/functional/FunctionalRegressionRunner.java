package com.makunai.safetygate.functional;

import com.makunai.safetygate.report.StageResult;
import com.makunai.safetygate.report.StageResult.Finding;
import com.makunai.safetygate.report.StageResult.Finding.Severity;
import com.makunai.safetygate.util.HttpUtil;
import com.makunai.safetygate.util.Json;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

/**
 * Stage 3 — Functional Regression.
 *
 * Executes the existing test-case matrix (functional, edge-case, auth) scoped
 * to the endpoints affected by this PR. Test cases are declared in JSON so
 * non-Java teammates (or Excel exports) can extend coverage without touching
 * code — this mirrors the 92-case-workbook style already in use for
 * manual-push-count.
 *
 * Expected file format (functional-cases.json):
 * [
 *   {
 *     "endpoint": "GET /choutbound-service/v1/manual-push-count",
 *     "name": "valid subscriber returns 200",
 *     "method": "GET",
 *     "path": "/choutbound-service/v1/manual-push-count?subscriber_name=abc",
 *     "requiresAuth": true,
 *     "expectedStatus": 200,
 *     "expectedBodyContains": ["push_count"]
 *   }
 * ]
 */
public final class FunctionalRegressionRunner {

    private final String baseUrl;
    private final String accessToken;

    public FunctionalRegressionRunner(String baseUrl, String accessToken) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.accessToken = accessToken;
    }

    @SuppressWarnings("unchecked")
    public StageResult run(Path casesFile, Set<String> scopedEndpoints) throws Exception {
        StageResult result = new StageResult("Functional Regression");
        long start = System.currentTimeMillis();

        List<Object> cases = (List<Object>) Json.parse(Files.readString(casesFile));
        int executed = 0;

        for (Object o : cases) {
            Map<String, Object> tc = (Map<String, Object>) o;
            String endpointKey = tc.get("endpoint").toString();

            // Only run cases for endpoints touched by this PR, unless scope is empty (run everything).
            if (!scopedEndpoints.isEmpty() && !scopedEndpoints.contains(endpointKey)) {
                continue;
            }
            executed++;

            String method = tc.get("method").toString();
            String path = tc.get("path").toString();
            boolean requiresAuth = Boolean.TRUE.equals(tc.get("requiresAuth"));
            int expectedStatus = ((Number) tc.get("expectedStatus")).intValue();
            List<Object> expectedContains = (List<Object>) tc.getOrDefault("expectedBodyContains", List.of());
            String caseName = tc.getOrDefault("name", endpointKey).toString();

            try {
                HttpUtil.TimedResponse resp = HttpUtil.request(
                        method, baseUrl + path, requiresAuth ? accessToken : null, null, Duration.ofSeconds(15));

                if (resp.statusCode != expectedStatus) {
                    result.addFinding(new Finding(Severity.HIGH, endpointKey,
                            "Regression: " + caseName,
                            "Expected status " + expectedStatus + " but got " + resp.statusCode +
                            ". Body: " + truncate(resp.body)));
                    continue;
                }

                boolean allFound = true;
                for (Object c : expectedContains) {
                    if (!resp.body.contains(c.toString())) {
                        allFound = false;
                        result.addFinding(new Finding(Severity.MEDIUM, endpointKey,
                                "Response missing expected field/value: " + c,
                                "Case '" + caseName + "' expected response to contain '" + c + "'"));
                    }
                }
                if (allFound) {
                    result.addFinding(new Finding(Severity.INFO, endpointKey,
                            "Passed: " + caseName, "Status " + resp.statusCode + " in " + resp.durationMillis + "ms"));
                }
            } catch (Exception e) {
                result.addFinding(new Finding(Severity.CRITICAL, endpointKey,
                        "Request failed: " + caseName, e.getMessage()));
            }
        }

        if (executed == 0) {
            result.addFinding(new Finding(Severity.INFO, "-",
                    "No functional cases matched scoped endpoints", "Nothing to run for this PR's changes."));
        }

        result.durationMillis = System.currentTimeMillis() - start;
        return result;
    }

    private static String truncate(String s) {
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }
}
