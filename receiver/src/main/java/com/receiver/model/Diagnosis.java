package com.receiver.model;

public record Diagnosis(
        String fault_type,
        String culprit_class,
        int culprit_line,
        int[] top3_lines,
        String root_cause_explanation,
        String suggested_remediation
) {}