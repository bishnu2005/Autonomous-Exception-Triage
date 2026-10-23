package com.autonomous.triage.otel;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collection;
import java.util.Map;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;

public final class ExceptionSpanExporter implements SpanExporter {

    private static final String DEFAULT_URL =
            "http://localhost:8081/api/telemetry/events";

    private final SpanExporter delegate;
    private final HttpClient httpClient;
    private final URI receiverUri;

    public ExceptionSpanExporter(SpanExporter delegate) {
        this.delegate = delegate;

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();

        String configured = System.getenv().getOrDefault(
                "OTEL_TRIAGE_RECEIVER_URL",
                DEFAULT_URL
        );

        this.receiverUri = URI.create(configured);
    }

    @Override
    public CompletableResultCode export(Collection<SpanData> spans) {
        CompletableResultCode result = new CompletableResultCode();

        System.err.println(
                "🔥 TRIAGE EXTENSION EXPORT CALLED: " + spans.size() + " spans"
        );

        try {
            delegate.export(spans);

            for (SpanData span : spans) {

                System.err.println(
                        "🔥 SPAN EVENTS: " + span.getEvents().size()
                );

                for (EventData event : span.getEvents()) {

                    System.err.println(
                            "🔥 EVENT NAME: " + event.getName()
                    );

                    if (!"exception".equals(event.getName())) {
                        continue;
                    }

                    Map<AttributeKey<?>, Object> attributes =
                            event.getAttributes().asMap();

                    System.err.println("🔥 EXCEPTION TYPE: "
                            + stringValue(attributes, "exception.type"));

                    System.err.println("🔥 EXCEPTION MESSAGE: "
                            + stringValue(attributes, "exception.message"));

                    System.err.println("🔥 EXCEPTION STACKTRACE: "
                            + stringValue(attributes, "exception.stacktrace"));
                    System.err.println("🔥 ALL EVENT ATTRIBUTES: " + attributes);

                    String type = stringValue(
                            attributes,
                            "exception.type"
                    );

                    String message = stringValue(
                            attributes,
                            "exception.message"
                    );

                    String stacktrace = stringValue(
                            attributes,
                            "exception.stacktrace"
                    );

                    if (stacktrace == null || stacktrace.isBlank()) {
                        continue;
                    }

                    String payload = toJson(
                            type,
                            message,
                            stacktrace,
                            span
                    );

                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(receiverUri)
                            .timeout(Duration.ofSeconds(15))
                            .header(
                                    "Content-Type",
                                    "application/json"
                            )
                            .POST(
                                    HttpRequest.BodyPublishers.ofString(
                                            payload
                                    )
                            )
                            .build();

                    HttpResponse<String> response =
                            httpClient.send(
                                    request,
                                    HttpResponse.BodyHandlers.ofString()
                            );

                    if (response.statusCode() >= 400) {
                        throw new IOException(
                                "Receiver returned HTTP "
                                        + response.statusCode()
                        );
                    }
                }
            }

            result.succeed();

        } catch (Exception e) {
            result.fail();
        }

        return result;
    }

    private static String stringValue(
            Map<AttributeKey<?>, Object> attributes,
            String key) {

        for (Map.Entry<AttributeKey<?>, Object> entry :
                attributes.entrySet()) {

            if (entry.getKey().getKey().equals(key)) {

                Object value = entry.getValue();

                return value == null
                        ? null
                        : value.toString();
            }
        }

        return null;
    }

    private static String toJson(
            String type,
            String message,
            String stacktrace,
            SpanData span) {

        return "{"
                + "\"event_type\":\"exception\","
                + "\"exception_type\":" + quote(type) + ","
                + "\"exception_message\":" + quote(message) + ","
                + "\"exception_stacktrace\":" + quote(stacktrace) + ","
                + "\"span_name\":" + quote(span.getName()) + ","
                + "\"sent_at_epoch_ms\":"
                + System.currentTimeMillis()
                + "}";
    }

    private static String quote(String value) {

        if (value == null) {
            return "null";
        }

        StringBuilder out = new StringBuilder("\"");

        for (int i = 0; i < value.length(); i++) {

            char c = value.charAt(i);

            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> out.append(c);
            }
        }

        return out.append("\"").toString();
    }

    @Override
    public CompletableResultCode flush() {
        return delegate.flush();
    }

    @Override
    public CompletableResultCode shutdown() {
        return delegate.shutdown();
    }
}