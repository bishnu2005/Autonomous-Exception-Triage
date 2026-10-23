package com.receiver.model;

public record TelemetryResult(
        String event_id,
        String exception_type,
        int raw_frames,
        int filtered_frames,
        double noise_reduction_percent,
        long received_at_epoch_ms,
        long llm_completed_at_epoch_ms,
        double latency_seconds,
        Diagnosis diagnosis
) {}