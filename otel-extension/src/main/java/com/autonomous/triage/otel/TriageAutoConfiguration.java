package com.autonomous.triage.otel;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
public final class TriageAutoConfiguration implements AutoConfigurationCustomizerProvider {
    @Override public void customize(AutoConfigurationCustomizer customizer) {
        customizer.addSpanExporterCustomizer((delegate, config) -> new ExceptionSpanExporter(delegate));
    }
}
