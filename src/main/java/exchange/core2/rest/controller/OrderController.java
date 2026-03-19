package exchange.core2.rest.controller;

import exchange.core2.core.ExchangeApi;
import exchange.core2.core.common.api.*;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.rest.dto.ApiResponse;
import exchange.core2.rest.dto.PlaceOrderRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.concurrent.ExecutionException;

/**
 * REST controller for order management operations.
 *
 * ENDPOINTS:
 * - POST   /api/orders         - Place a new order
 * - DELETE /api/orders/{orderId}  - Cancel an order
 * - PUT    /api/orders/{orderId}/move  - Move order to new price
 * - PUT    /api/orders/{orderId}/reduce  - Reduce order size
 *
 * USAGE EXAMPLES:
 *
 * Place BID order:
 *   curl -X POST http://localhost:8080/api/orders \
 *     -H "Content-Type: application/json" \
 *     -d '{
 *       "uid":301,
 *       "orderId":5001,
 *       "symbol":241,
 *       "price":15400,
 *       "size":12,
 *       "action":"BID",
 *       "orderType":"GTC",
 *       "reservePrice":15600
 *     }'
 *
 * Place ASK order:
 *   curl -X POST http://localhost:8080/api/orders \
 *     -H "Content-Type: application/json" \
 *     -d '{
 *       "uid":302,
 *       "orderId":5002,
 *       "symbol":241,
 *       "price":15250,
 *       "size":10,
 *       "action":"ASK",
 *       "orderType":"IOC"
 *     }'
 *
 * Cancel order:
 *   curl -X DELETE http://localhost:8080/api/orders/5001?uid=301&symbol=241
 *
 * Move order to new price:
 *   curl -X PUT http://localhost:8080/api/orders/5001/move?uid=301&symbol=241&newPrice=15300
 */
@Slf4j
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final ExchangeApi exchangeApi;

    /**
     * Place a new order.
     *
     * @param request Order details
     * @return Success/failure response
     */
    @PostMapping
    public ApiResponse<String> placeOrder(@RequestBody PlaceOrderRequest request) {
        log.info("Placing order: uid={}, orderId={}, symbol={}, {}@{} {}",
                request.getUid(),
                request.getOrderId(),
                request.getSymbol(),
                request.getSize(),
                request.getPrice(),
                request.getAction());

        try {
            // Build the API command
            ApiPlaceOrder.ApiPlaceOrderBuilder builder = ApiPlaceOrder.builder()
                    .uid(request.getUid())
                    .orderId(request.getOrderId())
                    .symbol(request.getSymbol())
                    .price(request.getPrice())
                    .size(request.getSize())
                    .action(request.getAction())
                    .orderType(request.getOrderType());

            // Add optional fields if present
            if (request.getReservePrice() != null) {
                builder.reservePrice(request.getReservePrice());
            }
            if (request.getUserCookie() != null) {
                builder.userCookie(request.getUserCookie());
            }

            // Submit command
            CommandResultCode result = exchangeApi.submitCommandAsync(builder.build()).get();

            if (result == CommandResultCode.SUCCESS) {
                return ApiResponse.success(
                        "Order placed successfully",
                        String.format("orderId=%d, symbol=%d", request.getOrderId(), request.getSymbol())
                );
            } else {
                return ApiResponse.error("Failed to place order", result.toString());
            }

        } catch (InterruptedException | ExecutionException e) {
            log.error("Error placing order orderId={}", request.getOrderId(), e);
            return ApiResponse.error("Error placing order", e.getMessage());
        }
    }

    /**
     * Cancel an existing order.
     *
     * @param orderId Order ID to cancel
     * @param uid User ID who owns the order
     * @param symbol Symbol ID
     * @return Success/failure response
     */
    @DeleteMapping("/{orderId}")
    public ApiResponse<String> cancelOrder(
            @PathVariable long orderId,
            @RequestParam long uid,
            @RequestParam int symbol) {

        log.info("Cancelling order: uid={}, orderId={}, symbol={}", uid, orderId, symbol);

        try {
            CommandResultCode result = exchangeApi.submitCommandAsync(
                    ApiCancelOrder.builder()
                            .uid(uid)
                            .orderId(orderId)
                            .symbol(symbol)
                            .build()
            ).get();

            if (result == CommandResultCode.SUCCESS) {
                return ApiResponse.success("Order cancelled successfully", "orderId=" + orderId);
            } else {
                return ApiResponse.error("Failed to cancel order", result.toString());
            }

        } catch (InterruptedException | ExecutionException e) {
            log.error("Error cancelling order orderId={}", orderId, e);
            return ApiResponse.error("Error cancelling order", e.getMessage());
        }
    }

    /**
     * Move an existing order to a new price level.
     *
     * @param orderId Order ID to move
     * @param uid User ID who owns the order
     * @param symbol Symbol ID
     * @param newPrice New price level
     * @return Success/failure response
     */
    @PutMapping("/{orderId}/move")
    public ApiResponse<String> moveOrder(
            @PathVariable long orderId,
            @RequestParam long uid,
            @RequestParam int symbol,
            @RequestParam long newPrice) {

        log.info("Moving order: uid={}, orderId={}, symbol={}, newPrice={}",
                uid, orderId, symbol, newPrice);

        try {
            CommandResultCode result = exchangeApi.submitCommandAsync(
                    ApiMoveOrder.builder()
                            .uid(uid)
                            .orderId(orderId)
                            .symbol(symbol)
                            .newPrice(newPrice)
                            .build()
            ).get();

            if (result == CommandResultCode.SUCCESS) {
                return ApiResponse.success(
                        "Order moved successfully",
                        String.format("orderId=%d, newPrice=%d", orderId, newPrice)
                );
            } else {
                return ApiResponse.error("Failed to move order", result.toString());
            }

        } catch (InterruptedException | ExecutionException e) {
            log.error("Error moving order orderId={}", orderId, e);
            return ApiResponse.error("Error moving order", e.getMessage());
        }
    }

    /**
     * Reduce the size of an existing order.
     *
     * @param orderId Order ID to reduce
     * @param uid User ID who owns the order
     * @param symbol Symbol ID
     * @param reduceBy Amount to reduce by (in lots)
     * @return Success/failure response
     */
    @PutMapping("/{orderId}/reduce")
    public ApiResponse<String> reduceOrder(
            @PathVariable long orderId,
            @RequestParam long uid,
            @RequestParam int symbol,
            @RequestParam long reduceBy) {

        log.info("Reducing order: uid={}, orderId={}, symbol={}, reduceBy={}",
                uid, orderId, symbol, reduceBy);

        try {
            CommandResultCode result = exchangeApi.submitCommandAsync(
                    ApiReduceOrder.builder()
                            .uid(uid)
                            .orderId(orderId)
                            .symbol(symbol)
                            .reduceSize(reduceBy)
                            .build()
            ).get();

            if (result == CommandResultCode.SUCCESS) {
                return ApiResponse.success(
                        "Order reduced successfully",
                        String.format("orderId=%d, reducedBy=%d", orderId, reduceBy)
                );
            } else {
                return ApiResponse.error("Failed to reduce order", result.toString());
            }

        } catch (InterruptedException | ExecutionException e) {
            log.error("Error reducing order orderId={}", orderId, e);
            return ApiResponse.error("Error reducing order", e.getMessage());
        }
    }
}
