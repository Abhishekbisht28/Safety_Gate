package com.makunai.safetygate.security;

import java.util.List;

/**
 * Standard, publicly-known QA payload strings used for defensive regression
 * testing of the team's own API (SQLi/XSS input-sanitization checks, IDOR
 * probes, JWT tampering). These are the same classes of payloads used by
 * OWASP ZAP / Burp Suite baseline scans — the goal is to confirm the backend
 * rejects or safely handles them, not to build an exploit.
 */
public final class PayloadLibrary {

    private PayloadLibrary() {
    }

    public static List<String> sqlInjectionProbes() {
        return List.of(
                "' OR '1'='1",
                "1; DROP TABLE users;--",
                "' UNION SELECT NULL--",
                "admin'--"
        );
    }

    public static List<String> xssProbes() {
        return List.of(
                "<script>alert(1)</script>",
                "\"><img src=x onerror=alert(1)>",
                "javascript:alert(1)"
        );
    }

    /** Malformed/tampered JWTs used to confirm the backend properly validates tokens. */
    public static List<String> jwtTamperCases() {
        return List.of(
                "eyJhbGciOiJub25lIn0.eyJzdWIiOiJhZG1pbiJ9.",           // alg:none attack
                "not-a-valid-jwt-at-all",
                "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.tamperedSignature"
        );
    }
}
