package com.makunai.safetygate.report;

import java.util.ArrayList;
import java.util.List;

/**
 * Outcome of a single pipeline stage (contract, functional, performance, security).
 */
public final class StageResult {

    public enum Status { PASS, FAIL, WARN, SKIPPED }

    public final String stageName;
    public Status status = Status.PASS;
    public final List<Finding> findings = new ArrayList<>();
    public long durationMillis;

    public StageResult(String stageName) {
        this.stageName = stageName;
    }

    public void addFinding(Finding f) {
        findings.add(f);
        if (f.severity == Finding.Severity.CRITICAL || f.severity == Finding.Severity.HIGH) {
            status = Status.FAIL;
        } else if (status != Status.FAIL && f.severity == Finding.Severity.MEDIUM) {
            status = Status.WARN;
        }
    }

    public boolean isFailing() {
        return status == Status.FAIL;
    }

    /** A single issue discovered during a stage (e.g. one breaking API change, one slow endpoint). */
    public static final class Finding {
        public enum Severity { CRITICAL, HIGH, MEDIUM, LOW, INFO }

        public final Severity severity;
        public final String endpoint;
        public final String message;
        public final String detail;

        public Finding(Severity severity, String endpoint, String message, String detail) {
            this.severity = severity;
            this.endpoint = endpoint;
            this.message = message;
            this.detail = detail;
        }
    }
}
