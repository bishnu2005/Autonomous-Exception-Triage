package com.receiver.service;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

@Service
public class StackFrameFilter {

 private static final List<String> BLOCKED = List.of(
         "java.",
         "javax.",
         "org.springframework.",
         "org.apache.tomcat.",
         "io.opentelemetry.",
         "jdk.internal."
 );

 public List<String> filter(String raw) {
  return Arrays.stream(raw.split("\\R"))
          .map(String::trim)
          .filter(s -> s.startsWith("at "))
          .filter(s -> !BLOCKED.stream().anyMatch(s::contains))
          .filter(s -> s.contains("com.sampleapp."))
          .collect(Collectors.toList());
 }

 public int rawFrameCount(String raw) {
  return (int) Arrays.stream(raw.split("\\R"))
          .map(String::trim)
          .filter(s -> s.startsWith("at "))
          .count();
 }
}