package com.receiver.controller;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.receiver.model.*;
import com.receiver.service.*;

@RestController
@RequestMapping("/api/telemetry")
public class TelemetryController {
 private final StackFrameFilter filter;
 private final OpenRouterClient llm;
 private final ObjectMapper mapper;
 private final Map<String, TelemetryResult> results = new ConcurrentHashMap<>();

 public TelemetryController(
         StackFrameFilter filter,
         OpenRouterClient llm,
         ObjectMapper mapper) {
  this.filter = filter;
  this.llm = llm;
  this.mapper = mapper;
 }

 @PostMapping("/events")
 public ResponseEntity<String> receive(
         @RequestBody TelemetryEvent event) {

  long received = System.currentTimeMillis();
  String id = UUID.randomUUID().toString();

  int raw = filter.rawFrameCount(event.exception_stacktrace());
  var frames = filter.filter(event.exception_stacktrace());

  CompletableFuture.runAsync(() -> {
   try {
    Diagnosis diagnosis = mapper.readValue(
            llm.diagnose(
                    event.exception_type(),
                    event.exception_message(),
                    String.join(
                            System.lineSeparator(),
                            frames
                    )
            ),
            Diagnosis.class
    );

    long completed = System.currentTimeMillis();

    double latency = event.sent_at_epoch_ms() == null
            ? (completed - received) / 1000.0
            : (completed - event.sent_at_epoch_ms()) / 1000.0;

    TelemetryResult result = new TelemetryResult(
            id,
            event.exception_type(),
            raw,
            frames.size(),
            raw == 0
                    ? 0
                    : ((raw - frames.size()) * 100.0 / raw),
            received,
            completed,
            latency,
            diagnosis
    );

    results.put(id, result);

   } catch (Exception e) {
    e.printStackTrace();
   }
  });

  return ResponseEntity.accepted().body(id);
 }

 @GetMapping("/results/{id}")
 public ResponseEntity<TelemetryResult> get(
         @PathVariable String id) {

  var r = results.get(id);

  return r == null
          ? ResponseEntity.notFound().build()
          : ResponseEntity.ok(r);
 }

 @GetMapping("/results/latest")
 public ResponseEntity<TelemetryResult> latest() {
  return results.values()
          .stream()
          .reduce((a, b) ->
                  a.received_at_epoch_ms()
                          > b.received_at_epoch_ms()
                          ? a
                          : b)
          .map(ResponseEntity::ok)
          .orElseGet(() ->
                  ResponseEntity.notFound().build());
 }
}