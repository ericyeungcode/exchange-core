package exchange.core2.rest.controller;

import exchange.core2.core.ExchangeApi;
import exchange.core2.core.common.L2MarketData;
import exchange.core2.core.common.api.reports.*;
import exchange.core2.rest.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.concurrent.ExecutionException;

/**
 * REST controller for queries and reports.
 *
 * ENDPOINTS:
 * - GET /api/orderbook/{symbol}  - Get order book for a symbol
 * - GET /api/users/{uid}/report  - Get user account report
 * - GET /api/reports/balances    - Get total currency balances
 * - GET /api/reports/state-hash  - Get state hash (for verification)
 *
 * USAGE EXAMPLES:
 *
 * Get order book (top 10 levels):
 *   curl http://localhost:8080/api/orderbook/241?depth=10
 *
 * Get user report:
 *   curl http://localhost:8080/api/users/301/report
 *
 * Get total balances:
 *   curl http://localhost:8080/api/reports/balances
 *
 * Get state hash:
 *   curl http://localhost:8080/api/reports/state-hash
 */
@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class QueryController {

    private final ExchangeApi exchangeApi;

    /**
     * Get order book for a symbol.
     *
     * @param symbol Symbol ID
     * @param depth Number of price levels to return (default: 10)
     * @return Order book with bids and asks
     */
    @GetMapping("/orderbook/{symbol}")
    public ApiResponse<L2MarketData> getOrderBook(
            @PathVariable int symbol,
            @RequestParam(defaultValue = "10") int depth) {

        log.info("Getting order book: symbol={}, depth={}", symbol, depth);

        try {
            L2MarketData orderBook = exchangeApi.requestOrderBookAsync(symbol, depth).get();

            return ApiResponse.success("Order book retrieved successfully", orderBook);

        } catch (InterruptedException | ExecutionException e) {
            log.error("Error getting order book for symbol={}", symbol, e);
            return ApiResponse.error("Error getting order book", e.getMessage());
        }
    }

    /**
     * Get user account report (balances, positions, active orders).
     *
     * @param uid User ID
     * @return User account report
     */
    @GetMapping("/users/{uid}/report")
    public ApiResponse<SingleUserReportResult> getUserReport(@PathVariable long uid) {
        log.info("Getting user report: uid={}", uid);

        try {
            SingleUserReportResult report = exchangeApi.processReport(
                    new SingleUserReportQuery(uid),
                    0  // timeout (0 = no timeout)
            ).get();

            return ApiResponse.success("User report retrieved successfully", report);

        } catch (InterruptedException | ExecutionException e) {
            log.error("Error getting report for uid={}", uid, e);
            return ApiResponse.error("Error getting user report", e.getMessage());
        }
    }

    /**
     * Get total currency balances across all users.
     * Useful for reconciliation and accounting.
     *
     * @return Total balances and fees collected per currency
     */
    @GetMapping("/reports/balances")
    public ApiResponse<TotalCurrencyBalanceReportResult> getTotalBalances() {
        log.info("Getting total currency balances");

        try {
            TotalCurrencyBalanceReportResult report = exchangeApi.processReport(
                    new TotalCurrencyBalanceReportQuery(),
                    0
            ).get();

            return ApiResponse.success("Total balances retrieved successfully", report);

        } catch (InterruptedException | ExecutionException e) {
            log.error("Error getting total balances", e);
            return ApiResponse.error("Error getting total balances", e.getMessage());
        }
    }

    /**
     * Get state hash for verification.
     * Hash represents the complete state of the exchange.
     * Used to verify consistency across replicas or after crash recovery.
     *
     * @return State hash
     */
    @GetMapping("/reports/state-hash")
    public ApiResponse<StateHashReportResult> getStateHash() {
        log.info("Getting state hash");

        try {
            StateHashReportResult report = exchangeApi.processReport(
                    new StateHashReportQuery(),
                    0
            ).get();

            return ApiResponse.success("State hash retrieved successfully", report);

        } catch (InterruptedException | ExecutionException e) {
            log.error("Error getting state hash", e);
            return ApiResponse.error("Error getting state hash", e.getMessage());
        }
    }
}
