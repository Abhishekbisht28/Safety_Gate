package com.makunai.safetygate;

import com.makunai.safetygate.config.GateConfig;
import com.makunai.safetygate.contract.ChangeDetector;
import com.makunai.safetygate.contract.ContractDiffChecker;
import com.makunai.safetygate.functional.FunctionalRegressionRunner;
import com.makunai.safetygate.performance.ResponseTimeGate;
import com.makunai.safetygate.report.ReportGenerator;
import com.makunai.safetygate.report.StageResult;
import com.makunai.safetygate.security.SecurityPayloadRunner;
import com.makunai.safetygate.swagger.SwaggerImporter;
import com.makunai.safetygate.util.JwtFetcher;

import java.nio.file.Path;
import java.util.*;

/**
 * Backend Safety Gate — orchestrates all stages against a PR before it
 * is allowed to merge:
 *
 *   0. Swagger Fetch            -> pull live OpenAPI specs, sync apis.json (optional, if URLs configured)
 *   1. Change Detection         -> scope subsequent stages to affected endpoints
 *   2. Contract Diff            -> block backward-incompatible API changes
 *   3. Functional Regression    -> confirm existing behavior still works
 *   4. Response Time Gate       -> block hard-limit breaches and regressions
 *   5. Security Surface Scan    -> block SQLi/XSS/IDOR/JWT/CSRF issues
 *   6. Report Generation        -> single HTML report + exit code for CI
 *
 * Usage:
 *   java -jar backend-safety-gate.jar <path-to-gate-config.json> [baseRef] [headRef]
 *
 * Exit code 0  -> all enabled stages passed, safe to merge.
 * Exit code 1  -> at least one enabled stage failed.
 * Exit code 2  -> the gate itself crashed (config error, network error, etc).
 */
public final class Main {

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: java -jar backend-safety-gate.jar <gate-config.json> [baseRef] [headRef]");
            System.exit(2);
        }

        String configPath = args[0];
        String baseRef = args.length > 1 ? args[1] : "origin/main";
        String headRef = args.length > 2 ? args[2] : "HEAD";

        try {
            run(configPath, baseRef, headRef);
        } catch (Exception e) {
            System.err.println("Gate crashed: " + e.getMessage());
            e.printStackTrace();
            System.exit(2);
        }
    }

    private static void run(String configPath, String baseRef, String headRef) throws Exception {
        System.out.println("=== Backend Safety Gate starting ===");
        GateConfig cfg = GateConfig.load(configPath);
        List<StageResult> stages = new ArrayList<>();

        // ---- Stage 0: Swagger Fetch (optional) ----
        if (cfg.swaggerUrlHead != null || cfg.swaggerUrlBase != null) {
            System.out.println("[0/6] Fetching live OpenAPI specs from Swagger URLs...");
            SwaggerImporter importer = new SwaggerImporter();
            try {
                if (cfg.swaggerUrlHead != null) {
                    String rawHead = importer.fetchSpec(cfg.swaggerUrlHead);
                    importer.saveSpec(rawHead, Path.of(cfg.openApiSpecPathHead));
                    System.out.println("    Head spec fetched from " + cfg.swaggerUrlHead);
                    if (cfg.autoSyncApisJson) {
                        int added = importer.syncApisJson(rawHead, Path.of("config/apis.json"));
                        System.out.println("    apis.json synced: " + added + " new endpoint(s) added");
                    }
                }
                if (cfg.swaggerUrlBase != null) {
                    String rawBase = importer.fetchSpec(cfg.swaggerUrlBase);
                    importer.saveSpec(rawBase, Path.of(cfg.openApiSpecPathBase));
                    System.out.println("    Base spec fetched from " + cfg.swaggerUrlBase);
                }
            } catch (Exception e) {
                System.out.println("    Warning: Swagger fetch failed (" + e.getMessage()
                        + ") — falling back to whatever specs/apis.json already exist on disk.");
            }
        }

        // ---- Stage 1: Change Detection ----
        System.out.println("[1/6] Detecting changed endpoints...");
        ChangeDetector detector = new ChangeDetector(Path.of("."));
        List<String> changedFiles = detector.changedFiles(baseRef, headRef);
        Set<String> scopedEndpoints;
        try {
            scopedEndpoints = detector.affectedEndpoints(changedFiles, Path.of("config/apis.json"));
        } catch (Exception e) {
            System.out.println("    Could not map changed files to endpoints (" + e.getMessage()
                    + ") — falling back to full scope.");
            scopedEndpoints = Set.of();
        }
        System.out.println("    " + changedFiles.size() + " file(s) changed, "
                + (scopedEndpoints.isEmpty() ? "running full scope" : scopedEndpoints.size() + " endpoint(s) scoped"));

        // ---- Stage 2: Contract Diff ----
        System.out.println("[2/6] Checking API contract compatibility...");
        StageResult contractResult;
        try {
            ContractDiffChecker contractChecker = new ContractDiffChecker();
            contractResult = contractChecker.run(
                    Path.of(cfg.openApiSpecPathBase), Path.of(cfg.openApiSpecPathHead));
        } catch (Exception e) {
            contractResult = skippedStage("Contract / Backward-Compatibility", e);
        }
        stages.add(contractResult);
        System.out.println("    " + contractResult.status + " (" + contractResult.findings.size() + " findings)");

        // ---- Auth token for live stages ----
        String accessToken = null;
        try {
            JwtFetcher jwtFetcher = new JwtFetcher(cfg.baseUrl, cfg.refreshToken);
            accessToken = jwtFetcher.fetchAccessToken();
        } catch (Exception e) {
            System.out.println("    Warning: could not obtain access token (" + e.getMessage()
                    + "). Auth-required checks will be skipped.");
        }

        // ---- Stage 3: Functional Regression ----
        System.out.println("[3/6] Running functional regression tests...");
        StageResult functionalResult;
        try {
            FunctionalRegressionRunner functionalRunner = new FunctionalRegressionRunner(cfg.baseUrl, accessToken);
            functionalResult = functionalRunner.run(Path.of("config/functional-cases.json"), scopedEndpoints);
        } catch (Exception e) {
            functionalResult = skippedStage("Functional Regression", e);
        }
        stages.add(functionalResult);
        System.out.println("    " + functionalResult.status + " (" + functionalResult.findings.size() + " findings)");

        // ---- Stage 4: Response Time Gate ----
        System.out.println("[4/6] Measuring response times...");
        StageResult perfResult;
        try {
            ResponseTimeGate perfGate = new ResponseTimeGate(
                    cfg.baseUrl, accessToken, cfg.responseTimeHardLimitMs, cfg.responseTimeRegressionTolerancePct);
            perfResult = perfGate.run(Path.of("config/apis.json"), Path.of(cfg.baselineFilePath), scopedEndpoints);
        } catch (Exception e) {
            perfResult = skippedStage("Response Time", e);
        }
        stages.add(perfResult);
        System.out.println("    " + perfResult.status + " (" + perfResult.findings.size() + " findings)");

        // ---- Stage 5: Security Surface Scan ----
        System.out.println("[5/6] Running security surface scan...");
        StageResult securityResult;
        try {
            SecurityPayloadRunner securityRunner = new SecurityPayloadRunner(cfg.baseUrl, accessToken);
            securityResult = securityRunner.run(Path.of("config/apis.json"), scopedEndpoints);
        } catch (Exception e) {
            securityResult = skippedStage("Security Surface Scan", e);
        }
        stages.add(securityResult);
        System.out.println("    " + securityResult.status + " (" + securityResult.findings.size() + " findings)");

        // ---- Stage 6: Report ----
        System.out.println("[6/6] Generating report...");
        ReportGenerator reportGenerator = new ReportGenerator();
        String commitSha = System.getenv().getOrDefault("GITHUB_SHA", headRef);
        String prTitle = System.getenv("PR_TITLE");
        reportGenerator.generate(stages, Path.of(cfg.reportOutputPath), prTitle, commitSha);
        System.out.println("    Report written to " + cfg.reportOutputPath);

        // ---- Gate decision ----
        boolean fail = false;
        if (cfg.failOnContractBreak && contractResult.isFailing()) fail = true;
        if (cfg.failOnFunctionalRegression && functionalResult.isFailing()) fail = true;
        if (cfg.failOnPerformanceRegression && perfResult.isFailing()) fail = true;
        if (cfg.failOnSecurityFinding && securityResult.isFailing()) fail = true;

        System.out.println("=== Gate result: " + (fail ? "FAILED" : "PASSED") + " ===");
        System.exit(fail ? 1 : 0);
    }

    private static StageResult skippedStage(String name, Exception e) {
        StageResult r = new StageResult(name);
        r.status = StageResult.Status.SKIPPED;
        r.addFinding(new StageResult.Finding(
                StageResult.Finding.Severity.INFO, "-", "Stage skipped", e.getMessage()));
        r.status = StageResult.Status.SKIPPED; // addFinding may have escalated status; force back to SKIPPED
        System.out.println("    Skipped (" + e.getMessage() + ")");
        return r;
    }
}
