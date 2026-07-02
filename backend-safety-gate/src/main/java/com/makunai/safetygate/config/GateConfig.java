package com.makunai.safetygate.config;

import com.makunai.safetygate.util.Json;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Loads pipeline configuration from config/gate-config.json, with
 * environment-variable overrides for anything secret (tokens, URLs).
 */
public final class GateConfig {

    public String baseUrl;
    public String refreshToken;
    public long responseTimeHardLimitMs;
    public double responseTimeRegressionTolerancePct;
    public String baselineFilePath;
    public String openApiSpecPathBase;   // spec on target/base branch (fetched from swaggerUrlBase, or static file)
    public String openApiSpecPathHead;   // spec on PR/head branch (fetched from swaggerUrlHead, or static file)
    public String swaggerUrlBase;        // e.g. currently-deployed env: https://collegehai-outbound-int.makunaiglobal.ai/api/schema/?format=json
    public String swaggerUrlHead;        // e.g. PR's local test server: http://localhost:8000/api/schema/?format=json
    public boolean autoSyncApisJson;     // if true, syncs newly-discovered endpoints from swaggerUrlHead into apis.json
    public String changedFilesListPath;  // output of `git diff --name-only`
    public List<String> criticalEndpointPrefixes; // never allowed to regress, regardless of scope
    public String reportOutputPath;
    public boolean failOnFunctionalRegression;
    public boolean failOnSecurityFinding;
    public boolean failOnContractBreak;
    public boolean failOnPerformanceRegression;

    public static GateConfig load(String configPath) throws Exception {
        GateConfig cfg = new GateConfig();
        Map<String, Object> raw = Json.parseObject(Files.readString(Path.of(configPath)));

        cfg.baseUrl = str(raw, "baseUrl", "http://localhost:8000");
        cfg.responseTimeHardLimitMs = (long) num(raw, "responseTimeHardLimitMs", 800);
        cfg.responseTimeRegressionTolerancePct = num(raw, "responseTimeRegressionTolerancePct", 15.0);
        cfg.baselineFilePath = str(raw, "baselineFilePath", "baseline/response-time-baseline.json");
        cfg.openApiSpecPathBase = str(raw, "openApiSpecPathBase", "specs/base-openapi.json");
        cfg.openApiSpecPathHead = str(raw, "openApiSpecPathHead", "specs/head-openapi.json");
        cfg.swaggerUrlBase = str(raw, "swaggerUrlBase", null);
        cfg.swaggerUrlHead = str(raw, "swaggerUrlHead", null);
        cfg.autoSyncApisJson = bool(raw, "autoSyncApisJson", true);
        cfg.changedFilesListPath = str(raw, "changedFilesListPath", "changed-files.txt");
        cfg.reportOutputPath = str(raw, "reportOutputPath", "reports/gate-report.html");
        cfg.failOnFunctionalRegression = bool(raw, "failOnFunctionalRegression", true);
        cfg.failOnSecurityFinding = bool(raw, "failOnSecurityFinding", true);
        cfg.failOnContractBreak = bool(raw, "failOnContractBreak", true);
        cfg.failOnPerformanceRegression = bool(raw, "failOnPerformanceRegression", true);

        @SuppressWarnings("unchecked")
        List<Object> prefixesRaw = (List<Object>) raw.getOrDefault("criticalEndpointPrefixes", List.of());
        cfg.criticalEndpointPrefixes = prefixesRaw.stream().map(Object::toString).toList();

        // Environment overrides — secrets should never live in the JSON file.
        String envBase = System.getenv("BACKEND_BASE_URL");
        if (envBase != null && !envBase.isBlank()) cfg.baseUrl = envBase;

        String envSwaggerBase = System.getenv("SWAGGER_URL_BASE");
        if (envSwaggerBase != null && !envSwaggerBase.isBlank()) cfg.swaggerUrlBase = envSwaggerBase;

        String envSwaggerHead = System.getenv("SWAGGER_URL_HEAD");
        if (envSwaggerHead != null && !envSwaggerHead.isBlank()) cfg.swaggerUrlHead = envSwaggerHead;

        cfg.refreshToken = System.getenv("DJANGO_REFRESH_TOKEN");

        return cfg;
    }

    private static String str(Map<String, Object> m, String key, String def) {
        Object v = m.get(key);
        return v == null ? def : v.toString();
    }

    private static double num(Map<String, Object> m, String key, double def) {
        Object v = m.get(key);
        return v == null ? def : ((Number) v).doubleValue();
    }

    private static boolean bool(Map<String, Object> m, String key, boolean def) {
        Object v = m.get(key);
        return v == null ? def : (Boolean) v;
    }
}
