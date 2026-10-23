package com.sampleapp;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
public class OrderController {
    private final JdbcTemplate jdbcTemplate;
    public OrderController(JdbcTemplate jdbcTemplate) { this.jdbcTemplate = jdbcTemplate; }

    @GetMapping("/null-pointer")
    public String nullPointer() {
        String payload = null;
        return payload.toUpperCase();
    }

    @GetMapping("/duplicate-key")
    public String duplicateKey() {
        jdbcTemplate.update("INSERT INTO orders (id, status) VALUES (?, ?)", 1L, "CREATED");
        jdbcTemplate.update("INSERT INTO orders (id, status) VALUES (?, ?)", 1L, "CREATED");
        return "unreachable";
    }

    @GetMapping("/shipped")
    public String shipped() {
        String status = "SHIPPED";
        if ("SHIPPED".equals(status)) {
            throw new IllegalStateException("Order is already marked as SHIPPED");
        }
        return "unreachable";
    }

    @GetMapping("/empty-items")
    public String emptyItems() {
        List<String> items = List.of();
        return items.get(4);
    }

    @GetMapping("/timeout")
    public String timeout() throws Exception {
        CompletableFuture<String> blockedOperation = CompletableFuture.supplyAsync(() -> {
            try {
                Thread.sleep(5000);
                return "completed";
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        });
        return blockedOperation.get(1, TimeUnit.SECONDS);
    }
}
