package com.makunai.safetygate.swagger;

import com.makunai.safetygate.util.HttpUtil;
import com.makunai.safetygate.util.Json;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

/**
 * Fetches a live OpenAPI/Swagger spec (e.g. Django REST Framework's
 * drf-spectacular output at /api/schema/ or /swagger.json) and:
 *
 *   1. Saves the raw spec to disk for Stage 2 (ContractDiffChecker) to diff.
 *   2. Syncs discovered endpoints into apis.json, which Stages 1/4/5 use for
 *      scoping, response-time checks, and security probes.
 *
 * Sync is additive/non-destructive: existing entries in apis.json (including
 * manually-curated "sourceFiles" and "ownerParam" fields) are never
 * overwritten. Only endpoints missing from apis.json are added, with
 * best-effort values inferred from the spec:
 *
 *   - requiresAuth   -> true if the operation (or the spec's global
 *                       security) declares a security requirement
 *   - queryParams    -> parameters with "in": "query"
 *   - ownerParam     -> heuristic: first query param containing "_by",
 *                       "_user", "user_id", or "owner" (case-insensitive)
 *   - sourceFiles    -> left empty; fill these in manually so Stage 1's
 *                       git-diff scoping can map code changes to this
 *                       endpoint. Until filled in, the endpoint is still
 *                       covered whenever the gate runs in full-scope mode.
 *
 * CLI usage:
 *   java -cp target/classes com.makunai.safetygate.swagger.SwaggerImporter \
 *        <swaggerUrl> <outputSpecPath> <apisJsonPath>
 */
public final class SwaggerImporter {

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: SwaggerImporter <swaggerUrl> <outputSpecPath> <apisJsonPath>");
            System.exit(2);
        }
        String swaggerUrl = args[0];
        Path outputSpecPath = Path.of(args[1]);
        Path apisJsonPath = Path.of(args[2]);

        SwaggerImporter importer = new SwaggerImporter();
        String rawSpec = importer.fetchSpec(swaggerUrl);
        importer.saveSpec(rawSpec, outputSpecPath);
        int added = importer.syncApisJson(rawSpec, apisJsonPath);
        System.out.println("Fetched spec from " + swaggerUrl + " -> " + outputSpecPath);
        System.out.println("Synced apis.json: " + added + " new endpoint(s) added, existing entries untouched.");
    }

    /** Fetches the raw OpenAPI/Swagger JSON from a URL (no auth — schema endpoints are typically public). */
    public String fetchSpec(String swaggerUrl) throws Exception {
        HttpUtil.TimedResponse resp = HttpUtil.request("GET", swaggerUrl, null, null, Duration.ofSeconds(20));
        if (resp.statusCode != 200) {
            throw new IllegalStateException(
                    "Failed to fetch Swagger spec from " + swaggerUrl + " (HTTP " + resp.statusCode + "). " +
                    "Check the URL is reachable and returns JSON (e.g. /api/schema/?format=json).");
        }
        // Basic sanity check — a JSON OpenAPI doc should parse as an object with "paths".
        Map<String, Object> parsed = Json.parseObject(resp.body);
        if (!parsed.containsKey("paths")) {
            throw new IllegalStateException(
                    "Response from " + swaggerUrl + " does not look like an OpenAPI spec (no 'paths' key). " +
                    "If this is DRF, try appending '?format=openapi-json' or use drf-spectacular's /api/schema/ route.");
        }
        return resp.body;
    }

    public void saveSpec(String rawSpec, Path outputSpecPath) throws Exception {
        Files.createDirectories(outputSpecPath.getParent() == null ? Path.of(".") : outputSpecPath.getParent());
        Files.writeString(outputSpecPath, rawSpec);
    }

    /**
     * Merges endpoints discovered in the spec into apis.json. Returns the
     * number of newly-added endpoints. Never mutates existing entries.
     */
    @SuppressWarnings("unchecked")
    public int syncApisJson(String rawSpec, Path apisJsonPath) throws Exception {
        Map<String, Object> spec = Json.parseObject(rawSpec);
        Map<String, Object> paths = (Map<String, Object>) spec.getOrDefault("paths", Map.of());
        boolean hasGlobalSecurity = spec.containsKey("security")
                && !((List<Object>) spec.getOrDefault("security", List.of())).isEmpty();

        Map<String, Object> apisRoot;
        List<Object> existingApis;
        if (Files.exists(apisJsonPath)) {
            apisRoot = Json.parseObject(Files.readString(apisJsonPath));
            existingApis = (List<Object>) apisRoot.getOrDefault("apis", new ArrayList<>());
        } else {
            apisRoot = new LinkedHashMap<>();
            existingApis = new ArrayList<>();
        }

        Set<String> existingKeys = new HashSet<>();
        for (Object o : existingApis) {
            Map<String, Object> api = (Map<String, Object>) o;
            existingKeys.add(api.get("method").toString().toUpperCase() + " " + api.get("path"));
        }

        int added = 0;
        List<Object> mergedApis = new ArrayList<>(existingApis);

        for (Map.Entry<String, Object> pathEntry : paths.entrySet()) {
            String path = pathEntry.getKey();
            Map<String, Object> methods = (Map<String, Object>) pathEntry.getValue();

            for (Map.Entry<String, Object> methodEntry : methods.entrySet()) {
                String method = methodEntry.getKey();
                if (!isHttpMethod(method)) continue; // skip "parameters", "description" etc siblings

                String key = method.toUpperCase() + " " + path;
                if (existingKeys.contains(key)) continue; // never overwrite manual edits

                Map<String, Object> operation = (Map<String, Object>) methodEntry.getValue();
                Map<String, Object> newEntry = buildEntry(method, path, operation, hasGlobalSecurity);
                mergedApis.add(newEntry);
                added++;
            }
        }

        apisRoot.put("apis", mergedApis);
        Files.createDirectories(apisJsonPath.getParent() == null ? Path.of(".") : apisJsonPath.getParent());
        Files.writeString(apisJsonPath, Json.write(apisRoot));
        return added;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildEntry(String method, String path, Map<String, Object> operation,
                                            boolean hasGlobalSecurity) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("method", method.toUpperCase());
        entry.put("path", path);

        boolean requiresAuth = hasGlobalSecurity;
        Object opSecurity = operation.get("security");
        if (opSecurity instanceof List) {
            requiresAuth = !((List<Object>) opSecurity).isEmpty();
        }
        entry.put("requiresAuth", requiresAuth);

        List<Object> parameters = (List<Object>) operation.getOrDefault("parameters", List.of());
        List<String> queryParams = new ArrayList<>();
        for (Object p : parameters) {
            Map<String, Object> param = (Map<String, Object>) p;
            if ("query".equals(param.get("in"))) {
                queryParams.add(param.get("name").toString());
            }
        }
        entry.put("queryParams", queryParams);

        String ownerParam = null;
        for (String qp : queryParams) {
            String lower = qp.toLowerCase();
            if (lower.contains("_by") || lower.contains("_user") || lower.contains("user_id") || lower.contains("owner")) {
                ownerParam = qp;
                break;
            }
        }
        if (ownerParam != null) {
            entry.put("ownerParam", ownerParam);
        }

        entry.put("sourceFiles", List.of()); // TODO: fill in manually for precise git-diff scoping
        return entry;
    }

    private boolean isHttpMethod(String s) {
        return switch (s.toLowerCase()) {
            case "get", "post", "put", "patch", "delete", "head", "options" -> true;
            default -> false;
        };
    }
}
