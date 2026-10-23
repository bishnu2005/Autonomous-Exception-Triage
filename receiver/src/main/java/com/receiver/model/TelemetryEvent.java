package com.receiver.model;
public record TelemetryEvent(String event_type,String exception_type,String exception_message,String exception_stacktrace,String span_name,Long sent_at_epoch_ms) {}
