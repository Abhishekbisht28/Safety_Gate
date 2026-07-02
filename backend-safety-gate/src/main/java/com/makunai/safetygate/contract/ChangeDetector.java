package com.makunai.safetygate.contract;

import com.makunai.safetygate.util.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Stage 1 — Change Detection.
 *
 * Runs `git diff --name-only` between base and head refs, then maps changed
 * source files (views, serializers, urls) to the API endpoints they affect,
 * using the same apis.json convention as api-response-time-tester. This
 * scopes every later stage to only the endpoints that could plausibly be
 * impacted by this PR, instead of re-testing the entire surface every time.
 */
public final class ChangeDetector {

    private final Path repoRoot;

    public ChangeDetector(Path repoRoot) {
        this.repoRoot = repoRoot;
    }

    /** Runs `git diff --name-only base...head` and returns changed file paths. */
    public List<String> changedFiles(String baseRef, String headRef) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(
                "git", "diff", "--name-only", baseRef + "..." + headRef)
                .directory(repoRoot.toFile())
                .redirectErrorStream(false);
        Process proc = pb.start();
        List<String> files = new ArrayList<>();
        try (var reader = proc.inputReader()) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) files.add(line.trim());
            }
        }
        int exit = proc.waitFor();
        if (exit != 0) {
            throw new IOException("git diff exited with code " + exit + " (base=" + baseRef + ", head=" + headRef + ")");
        }
        return files;
    }

    /**
     * Maps changed files to endpoint paths using apis.json, which lists each
     * endpoint alongside the source files it depends on:
     * [{ "path": "/choutbound-service/v1/manual-push-count", "method": "GET",
     *    "sourceFiles": ["views.py", "serializers/push.py"] }, ...]
     */
    @SuppressWarnings("unchecked")
    public Set<String> affectedEndpoints(List<String> changedFiles, Path apisJsonPath) throws IOException {
        Map<String, Object> raw = Json.parseObject(Files.readString(apisJsonPath));
        List<Object> apis = (List<Object>) raw.getOrDefault("apis", List.of());

        Set<String> normalizedChanged = new HashSet<>();
        for (String f : changedFiles) {
            normalizedChanged.add(Path.of(f).getFileName().toString());
            normalizedChanged.add(f);
        }

        Set<String> affected = new LinkedHashSet<>();
        for (Object o : apis) {
            Map<String, Object> api = (Map<String, Object>) o;
            List<Object> sourceFiles = (List<Object>) api.getOrDefault("sourceFiles", List.of());
            for (Object sf : sourceFiles) {
                String sourceFile = sf.toString();
                boolean matches = normalizedChanged.contains(sourceFile)
                        || normalizedChanged.contains(Path.of(sourceFile).getFileName().toString());
                if (matches) {
                    affected.add(api.get("method") + " " + api.get("path"));
                    break;
                }
            }
        }
        return affected;
    }
}
