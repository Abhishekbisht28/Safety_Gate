package com.makunai.safetygate.report;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Renders all stage results into a single self-contained HTML report
 * (no external CSS/JS dependencies, so it opens fine as an email attachment
 * or a GitHub Actions artifact).
 */
public final class ReportGenerator {

    public void generate(List<StageResult> stages, Path outputPath, String prTitle, String commitSha) throws Exception {
        Files.createDirectories(outputPath.getParent() == null ? Path.of(".") : outputPath.getParent());

        boolean overallPass = stages.stream().noneMatch(StageResult::isFailing);
        StringBuilder html = new StringBuilder();

        html.append("<!DOCTYPE html><html><head><meta charset='utf-8'>");
        html.append("<title>Backend Safety Gate Report</title>");
        html.append("<style>")
            .append("body{font-family:-apple-system,Segoe UI,Roboto,sans-serif;background:#0f172a;color:#e2e8f0;padding:24px;}")
            .append(".header{margin-bottom:24px;}")
            .append(".badge{display:inline-block;padding:6px 14px;border-radius:6px;font-weight:600;}")
            .append(".pass{background:#166534;color:#dcfce7;}")
            .append(".fail{background:#991b1b;color:#fee2e2;}")
            .append(".warn{background:#92400e;color:#fef3c7;}")
            .append(".stage{background:#1e293b;border-radius:10px;padding:16px 20px;margin-bottom:16px;}")
            .append(".stage h2{margin:0 0 8px 0;font-size:18px;display:flex;justify-content:space-between;}")
            .append("table{width:100%;border-collapse:collapse;margin-top:10px;}")
            .append("th,td{text-align:left;padding:8px;border-bottom:1px solid #334155;font-size:13px;vertical-align:top;}")
            .append("th{color:#94a3b8;font-weight:600;}")
            .append(".sev-CRITICAL{color:#f87171;font-weight:700;}")
            .append(".sev-HIGH{color:#fb923c;font-weight:600;}")
            .append(".sev-MEDIUM{color:#facc15;}")
            .append(".sev-LOW{color:#94a3b8;}")
            .append(".sev-INFO{color:#4ade80;}")
            .append(".meta{color:#94a3b8;font-size:13px;margin-bottom:4px;}")
            .append("</style></head><body>");

        html.append("<div class='header'>");
        html.append("<h1>Backend Safety Gate</h1>");
        html.append("<div class='meta'>Generated: ")
            .append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
            .append("</div>");
        if (prTitle != null) html.append("<div class='meta'>PR: ").append(escape(prTitle)).append("</div>");
        if (commitSha != null) html.append("<div class='meta'>Commit: ").append(escape(commitSha)).append("</div>");
        html.append("<span class='badge ").append(overallPass ? "pass" : "fail").append("'>")
            .append(overallPass ? "GATE PASSED" : "GATE FAILED")
            .append("</span>");
        html.append("</div>");

        for (StageResult stage : stages) {
            html.append("<div class='stage'>");
            html.append("<h2><span>").append(escape(stage.stageName)).append("</span>");
            html.append("<span class='badge ").append(cssClassFor(stage.status)).append("'>")
                .append(stage.status).append("</span></h2>");
            html.append("<div class='meta'>Duration: ").append(stage.durationMillis).append("ms · ")
                .append(stage.findings.size()).append(" finding(s)</div>");

            if (!stage.findings.isEmpty()) {
                html.append("<table><tr><th>Severity</th><th>Endpoint</th><th>Message</th><th>Detail</th></tr>");
                for (StageResult.Finding f : stage.findings) {
                    html.append("<tr>");
                    html.append("<td class='sev-").append(f.severity).append("'>").append(f.severity).append("</td>");
                    html.append("<td>").append(escape(f.endpoint)).append("</td>");
                    html.append("<td>").append(escape(f.message)).append("</td>");
                    html.append("<td>").append(escape(f.detail)).append("</td>");
                    html.append("</tr>");
                }
                html.append("</table>");
            }
            html.append("</div>");
        }

        html.append("</body></html>");
        Files.writeString(outputPath, html.toString());
    }

    private String cssClassFor(StageResult.Status status) {
        return switch (status) {
            case PASS -> "pass";
            case FAIL -> "fail";
            case WARN -> "warn";
            case SKIPPED -> "warn";
        };
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
