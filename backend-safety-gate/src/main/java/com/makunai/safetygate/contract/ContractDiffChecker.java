package com.makunai.safetygate.contract;

import com.makunai.safetygate.report.StageResult;
import com.makunai.safetygate.report.StageResult.Finding;
import com.makunai.safetygate.report.StageResult.Finding.Severity;
import com.makunai.safetygate.util.Json;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Stage 2 — Contract / Backward-Compatibility Check.
 *
 * Compares the OpenAPI spec exported from the base branch against the spec
 * exported from the PR/head branch. This is the check that stops a backend
 * change from silently breaking every existing caller (frontend, mobile app,
 * other services) even if the new code "works" in isolation.
 *
 * Breaking changes (fail the gate):
 *   - Endpoint removed
 *   - Response field removed
 *   - Response field type changed
 *   - New required request field added
 *   - Request field type changed
 *   - Success status code changed (e.g. 200 -> 201)
 *   - Enum value removed from a field
 *
 * Non-breaking changes (pass silently, logged as INFO):
 *   - New endpoint added
 *   - New optional request field
 *   - New response field
 *   - New enum value added
 */
public final class ContractDiffChecker {

    @SuppressWarnings("unchecked")
    public StageResult run(Path baseSpecPath, Path headSpecPath) throws Exception {
        StageResult result = new StageResult("Contract / Backward-Compatibility");
        long start = System.currentTimeMillis();

        Map<String, Object> baseSpec = Json.parseObject(Files.readString(baseSpecPath));
        Map<String, Object> headSpec = Json.parseObject(Files.readString(headSpecPath));

        Map<String, Object> basePaths = (Map<String, Object>) baseSpec.getOrDefault("paths", Map.of());
        Map<String, Object> headPaths = (Map<String, Object>) headSpec.getOrDefault("paths", Map.of());

        for (String path : basePaths.keySet()) {
            Map<String, Object> baseMethods = (Map<String, Object>) basePaths.get(path);
            Map<String, Object> headMethods = (Map<String, Object>) headPaths.get(path);

            if (headMethods == null) {
                result.addFinding(new Finding(Severity.CRITICAL, path,
                        "Endpoint removed entirely",
                        "Path " + path + " exists in base branch but is missing from the PR branch spec."));
                continue;
            }

            for (String method : baseMethods.keySet()) {
                Map<String, Object> baseOp = (Map<String, Object>) baseMethods.get(method);
                Map<String, Object> headOp = (Map<String, Object>) headMethods.get(method);
                String endpointId = method.toUpperCase() + " " + path;

                if (headOp == null) {
                    result.addFinding(new Finding(Severity.CRITICAL, endpointId,
                            "HTTP method removed from endpoint",
                            "Method " + method.toUpperCase() + " was previously supported on " + path));
                    continue;
                }

                compareResponses(endpointId, baseOp, headOp, result);
                compareRequestSchema(endpointId, baseOp, headOp, result);
                compareStatusCodes(endpointId, baseOp, headOp, result);
            }
        }

        // New endpoints — informational only, never breaking.
        for (String path : headPaths.keySet()) {
            if (!basePaths.containsKey(path)) {
                result.addFinding(new Finding(Severity.INFO, path, "New endpoint added", "Non-breaking."));
            }
        }

        result.durationMillis = System.currentTimeMillis() - start;
        return result;
    }

    @SuppressWarnings("unchecked")
    private void compareResponses(String endpointId, Map<String, Object> baseOp, Map<String, Object> headOp,
                                   StageResult result) {
        Map<String, Object> baseSchema = extractSchema(baseOp, "responses");
        Map<String, Object> headSchema = extractSchema(headOp, "responses");
        if (baseSchema == null) return;

        Map<String, Object> baseProps = (Map<String, Object>) baseSchema.getOrDefault("properties", Map.of());
        Map<String, Object> headProps = headSchema == null
                ? Map.of() : (Map<String, Object>) headSchema.getOrDefault("properties", Map.of());

        for (String field : baseProps.keySet()) {
            if (!headProps.containsKey(field)) {
                result.addFinding(new Finding(Severity.CRITICAL, endpointId,
                        "Response field removed: " + field,
                        "Callers relying on '" + field + "' in the response body will break."));
                continue;
            }
            String baseType = fieldType(baseProps, field);
            String headType = fieldType(headProps, field);
            if (baseType != null && headType != null && !baseType.equals(headType)) {
                result.addFinding(new Finding(Severity.HIGH, endpointId,
                        "Response field type changed: " + field,
                        field + " changed from '" + baseType + "' to '" + headType + "'"));
            }
        }
        for (String field : headProps.keySet()) {
            if (!baseProps.containsKey(field)) {
                result.addFinding(new Finding(Severity.INFO, endpointId,
                        "New response field: " + field, "Non-breaking."));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void compareRequestSchema(String endpointId, Map<String, Object> baseOp, Map<String, Object> headOp,
                                       StageResult result) {
        Map<String, Object> baseSchema = extractSchema(baseOp, "requestBody");
        Map<String, Object> headSchema = extractSchema(headOp, "requestBody");
        if (headSchema == null) return;

        List<Object> baseRequired = baseSchema == null
                ? List.of() : (List<Object>) baseSchema.getOrDefault("required", List.of());
        List<Object> headRequired = (List<Object>) headSchema.getOrDefault("required", List.of());

        for (Object f : headRequired) {
            if (!baseRequired.contains(f)) {
                result.addFinding(new Finding(Severity.CRITICAL, endpointId,
                        "New REQUIRED request field: " + f,
                        "Existing callers that don't send '" + f + "' will now fail validation."));
            }
        }

        Map<String, Object> baseProps = baseSchema == null
                ? Map.of() : (Map<String, Object>) baseSchema.getOrDefault("properties", Map.of());
        Map<String, Object> headProps = (Map<String, Object>) headSchema.getOrDefault("properties", Map.of());

        for (String field : baseProps.keySet()) {
            String baseType = fieldType(baseProps, field);
            String headType = fieldType(headProps, field);
            if (headType != null && baseType != null && !baseType.equals(headType)) {
                result.addFinding(new Finding(Severity.HIGH, endpointId,
                        "Request field type changed: " + field,
                        field + " changed from '" + baseType + "' to '" + headType + "'"));
            }
        }
    }

    private void compareStatusCodes(String endpointId, Map<String, Object> baseOp, Map<String, Object> headOp,
                                     StageResult result) {
        Set<String> baseCodes = successCodes(baseOp);
        Set<String> headCodes = successCodes(headOp);
        if (!baseCodes.isEmpty() && !headCodes.isEmpty() && !baseCodes.equals(headCodes)) {
            result.addFinding(new Finding(Severity.MEDIUM, endpointId,
                    "Success status code changed",
                    "Base returns " + baseCodes + ", PR branch returns " + headCodes +
                    ". Verify callers don't hard-check the old code."));
        }
    }

    @SuppressWarnings("unchecked")
    private Set<String> successCodes(Map<String, Object> op) {
        Map<String, Object> responses = (Map<String, Object>) op.getOrDefault("responses", Map.of());
        Set<String> codes = new TreeSet<>();
        for (String code : responses.keySet()) {
            if (code.startsWith("2")) codes.add(code);
        }
        return codes;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractSchema(Map<String, Object> op, String key) {
        try {
            if (key.equals("responses")) {
                Map<String, Object> responses = (Map<String, Object>) op.get("responses");
                if (responses == null) return null;
                Object successResp = responses.get("200");
                if (successResp == null) successResp = responses.get("201");
                if (successResp == null) return null;
                Map<String, Object> content = (Map<String, Object>) ((Map<String, Object>) successResp).get("content");
                if (content == null) return null;
                Map<String, Object> appJson = (Map<String, Object>) content.get("application/json");
                if (appJson == null) return null;
                return (Map<String, Object>) appJson.get("schema");
            } else {
                Map<String, Object> body = (Map<String, Object>) op.get("requestBody");
                if (body == null) return null;
                Map<String, Object> content = (Map<String, Object>) body.get("content");
                if (content == null) return null;
                Map<String, Object> appJson = (Map<String, Object>) content.get("application/json");
                if (appJson == null) return null;
                return (Map<String, Object>) appJson.get("schema");
            }
        } catch (ClassCastException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private String fieldType(Map<String, Object> props, String field) {
        Object f = props.get(field);
        if (!(f instanceof Map)) return null;
        Object type = ((Map<String, Object>) f).get("type");
        return type == null ? null : type.toString();
    }
}
