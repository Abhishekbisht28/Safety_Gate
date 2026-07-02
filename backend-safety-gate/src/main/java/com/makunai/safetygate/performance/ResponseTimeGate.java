package com.makunai.safetygate.performance;

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
 * Stage 4 — Response Time Gate.
 *
 * Two independent checks per scoped endpoint:
 *   1. Hard limit  — absolute ceiling (e.g. 800ms). Always enforced.
 *   2. Regression  — % slower than the stored baseline for that endpoint.
 *      Catches gradual creep even when still under the hard limit
 *      (e.g. baseline 200ms -> now 350ms is a 75% regression, still "fast"
 *      in absolute terms but a real problem).
 *
 * Each endpoint is called multiple times and the median is used, to avoid a
 * single cold-cache request failing the gate.
 */
public final class ResponseTimeGate {

    private final String baseUrl;
    private final String accessToken;
    private final long hardLimitMs;
    private final double regressionTolerancePct;
    private final int samplesPerEndpoint;

    public ResponseTimeGate(String baseUrl, String accessToken, long hardLimitMs,
                             double regressionTolerancePct) {
        this(baseUrl, accessToken, hardLimitMs, regressionTolerancePct, 3);
    }

    public ResponseTimeGate(String baseUrl, String accessToken, long hardLimitMs,
                             double regressionTolerancePct, int samplesPerEndpoint) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.accessToken = accessToken;
        this.hardLimitMs = hardLimitMs;
        this.regressionTolerancePct = regressionTolerancePct;
        this.samplesPerEndpoint = samplesPerEndpoint;
    }

    @SuppressWarnings("unchecked")
    public StageResult run(Path apisJsonPath, Path baselinePath, Set<String> scopedEndpoints) throws Exception {
        StageResult result = new StageResult("Response Time");
        long start = System.currentTimeMillis();

        Map<String, Object> apisRaw = Json.parseObject(Files.readString(apisJsonPath));
        List<Object> apis = (List<Object>) apisRaw.getOrDefault("apis", List.of());

        Map<String, Object> baseline = Files.exists(baselinePath)
                ? Json.parseObject(Files.readString(baselinePath))
                : new LinkedHashMap<>();

        Map<String, Object> updatedBaseline = new LinkedHashMap<>(baseline);

        for (Object o : apis) {
            Map<String, Object> api = (Map<String, Object>) o;
            String method = api.get("method").toString();
            String path = api.get("path").toString();
            String key = method + " " + path;

            if (!scopedEndpoints.isEmpty() && !scopedEndpoints.contains(key)) {
                continue;
            }

            boolean requiresAuth = Boolean.TRUE.equals(api.get("requiresAuth"));
            List<Long> samples = new ArrayList<>();
            int lastStatus = -1;

            for (int i = 0; i < samplesPerEndpoint; i++) {
                try {
                    HttpUtil.TimedResponse resp = HttpUtil.request(
                            method, baseUrl + path, requiresAuth ? accessToken : null, null, Duration.ofSeconds(20));
                    samples.add(resp.durationMillis);
                    lastStatus = resp.statusCode;
                } catch (Exception e) {
                    result.addFinding(new Finding(Severity.HIGH, key,
                            "Timing request failed", e.getMessage()));
                }
            }

            if (samples.isEmpty()) continue;

            Collections.sort(samples);
            long median = samples.get(samples.size() / 2);

            if (lastStatus >= 400) {
                result.addFinding(new Finding(Severity.MEDIUM, key,
                        "Timed request returned error status " + lastStatus,
                        "Response time still measured, but the endpoint did not succeed — investigate."));
            }

            // Hard limit check
            if (median > hardLimitMs) {
                result.addFinding(new Finding(Severity.HIGH, key,
                        "Exceeds hard limit",
                        "Median response time " + median + "ms exceeds hard limit of " + hardLimitMs + "ms"));
            }

            // Regression vs baseline check
            Object baselineVal = baseline.get(key);
            if (baselineVal != null) {
                double baselineMs = ((Number) baselineVal).doubleValue();
                double pctChange = ((median - baselineMs) / baselineMs) * 100.0;
                if (pctChange > regressionTolerancePct) {
                    result.addFinding(new Finding(Severity.HIGH, key,
                            "Performance regression vs baseline",
                            String.format("Baseline %.0fms -> now %dms (%.1f%% slower, tolerance is %.1f%%)",
                                    baselineMs, median, pctChange, regressionTolerancePct)));
                } else {
                    result.addFinding(new Finding(Severity.INFO, key,
                            "Within tolerance", String.format("%.0fms -> %dms (%.1f%% change)",
                                    baselineMs, median, pctChange)));
                }
            } else {
                result.addFinding(new Finding(Severity.INFO, key,
                        "No baseline yet — recording", median + "ms will become the new baseline."));
            }

            // Only ratchet the baseline forward when the run passed (don't bake in a regression).
            boolean endpointFailed = median > hardLimitMs
                    || (baselineVal != null && ((median - ((Number) baselineVal).doubleValue())
                            / ((Number) baselineVal).doubleValue()) * 100.0 > regressionTolerancePct);
            if (!endpointFailed) {
                updatedBaseline.put(key, (double) median);
            }
        }

        Files.createDirectories(baselinePath.getParent() == null ? Path.of(".") : baselinePath.getParent());
        Files.writeString(baselinePath, Json.write(updatedBaseline));

        result.durationMillis = System.currentTimeMillis() - start;
        return result;
    }
}
