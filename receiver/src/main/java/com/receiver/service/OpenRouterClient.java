package com.receiver.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class OpenRouterClient {

 private final String apiKey;
 private final String model;
 private final HttpClient client;
 private final ObjectMapper mapper;

 public OpenRouterClient(
         @Value("${groq.api-key}") String apiKey,
         @Value("${groq.model}") String model,
         ObjectMapper mapper) {

  this.apiKey = apiKey;
  this.model = model;
  this.mapper = mapper;

  this.client = HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(10))
          .build();
 }

 public String diagnose(
         String type,
         String message,
         String filtered) throws Exception {

  if (apiKey == null || apiKey.isBlank()) {
   throw new IllegalStateException(
           "GROQ_API_KEY is required for live LLM evaluation"
   );
  }

  String prompt =
          "You are an expert Java AIOps exception triage engine.\n"
                  + "Return ONLY valid JSON with exactly these keys: "
                  + "fault_type, culprit_class, culprit_line, top3_lines, "
                  + "root_cause_explanation, suggested_remediation.\n"
                  + "culprit_line must be an integer.\n"
                  + "top3_lines must be an array containing up to 3 integers.\n"
                  + "Select up to 3 of the most likely culprit line numbers "
                  + "strictly from the provided filtered application stack frames.\n"
                  + "Do not invent, infer, or generate line numbers that are not "
                  + "present in the filtered application stack.\n"
                  + "If fewer than 3 valid application stack frames are provided, "
                  + "return only the available line numbers.\n"
                  + "The culprit_line must be the single most likely culprit "
                  + "line and must also appear in top3_lines.\n"
                  + "Use the filtered application stack as primary evidence.\n"
                  + "Do not add Markdown fences or extra keys.\n"
                  + "Exception type: " + type + "\n"
                  + "Exception message: " + message + "\n"
                  + "Filtered stack trace:\n" + filtered;

  String body = mapper.writeValueAsString(
          new ChatRequest(
                  model,
                  new ChatMessage[]{
                          new ChatMessage(
                                  "system",
                                  "Return strict JSON only."
                          ),
                          new ChatMessage(
                                  "user",
                                  prompt
                          )
                  },
                  0.0
          )
  );

  HttpRequest req = HttpRequest.newBuilder(
                  URI.create(
                          "https://api.groq.com/openai/v1/chat/completions"
                  )
          )
          .timeout(Duration.ofSeconds(60))
          .header("Authorization", "Bearer " + apiKey)
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(body))
          .build();

  HttpResponse<String> resp = client.send(
          req,
          HttpResponse.BodyHandlers.ofString()
  );

  if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
   throw new IllegalStateException(
           "Groq HTTP " + resp.statusCode() + ": " + resp.body()
   );
  }

  JsonNode root = mapper.readTree(resp.body());

  String content = root
          .path("choices")
          .get(0)
          .path("message")
          .path("content")
          .asText()
          .trim();

  if (content.startsWith("```")) {
   int a = content.indexOf('\n');
   int b = content.lastIndexOf("```");

   if (a >= 0 && b > a) {
    content = content.substring(a + 1, b).trim();
   }
  }

  return mapper.writeValueAsString(
          mapper.readTree(content)
  );
 }

 private record ChatRequest(
         String model,
         ChatMessage[] messages,
         double temperature
 ) {}

 private record ChatMessage(
         String role,
         String content
 ) {}
}