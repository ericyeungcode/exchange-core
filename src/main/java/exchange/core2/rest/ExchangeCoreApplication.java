package exchange.core2.rest;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot application for Exchange Core REST API.
 *
 * OVERVIEW:
 * This application wraps the ExchangeCore library with a REST API layer,
 * allowing external clients to interact with the high-performance exchange
 * via HTTP endpoints.
 *
 * FEATURES:
 * - User management (create, suspend, resume users)
 * - Balance management (deposits, withdrawals)
 * - Order operations (place, cancel, move orders)
 * - Symbol/trading pair management
 * - Market data queries (order books, user reports)
 *
 * ARCHITECTURE:
 * - ExchangeCore runs in the background with Disruptor threads
 * - REST controllers submit commands asynchronously via ExchangeApi
 * - Results are returned via CompletableFuture (async) or blocking (sync)
 *
 * STARTUP:
 * mvn spring-boot:run
 * OR
 * java -jar exchange-core-rest.jar
 *
 * Default port: 8080
 * Base URL: http://localhost:8080
 */
@SpringBootApplication
public class ExchangeCoreApplication {

    public static void main(String[] args) {
        SpringApplication.run(ExchangeCoreApplication.class, args);
    }
}
