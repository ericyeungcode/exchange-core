package exchange.core2.rest.controller;

import exchange.core2.rest.dto.ApiResponse;
import lombok.Data;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Health check endpoint.
 *
 * ENDPOINTS:
 * - GET /health  - Basic health check
 * - GET /        - Welcome message with API documentation link
 */
@RestController
public class HealthController {

    private final Instant startTime = Instant.now();

    /**
     * Basic health check endpoint.
     *
     * @return Health status
     */
    @GetMapping("/health")
    public ApiResponse<HealthStatus> health() {
        HealthStatus status = new HealthStatus();
        status.setStatus("UP");
        status.setTimestamp(Instant.now());
        status.setUptime(Instant.now().getEpochSecond() - startTime.getEpochSecond());

        return ApiResponse.success("Exchange Core API is running", status);
    }

    /**
     * Welcome endpoint with API documentation.
     *
     * @return Welcome message
     */
    @GetMapping("/")
    public ApiResponse<Map<String, String>> welcome() {
        Map<String, String> info = new HashMap<>();
        info.put("service", "Exchange Core REST API");
        info.put("version", "0.5.4");
        info.put("status", "running");
        info.put("documentation", "See REST_API.md for complete API documentation");

        Map<String, String> endpoints = new HashMap<>();
        endpoints.put("users", "POST /api/users, POST /api/users/{uid}/balance, etc.");
        endpoints.put("orders", "POST /api/orders, DELETE /api/orders/{orderId}, etc.");
        endpoints.put("symbols", "POST /api/symbols");
        endpoints.put("queries", "GET /api/orderbook/{symbol}, GET /api/users/{uid}/report, etc.");
        endpoints.put("health", "GET /health");

        info.put("endpoints", endpoints.toString());

        return ApiResponse.success("Welcome to Exchange Core API", info);
    }

    @Data
    static class HealthStatus {
        private String status;
        private Instant timestamp;
        private long uptime; // seconds
    }
}
