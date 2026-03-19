package exchange.core2.rest.controller;

import exchange.core2.core.ExchangeApi;
import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.api.binary.BatchAddSymbolsCommand;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.rest.dto.ApiResponse;
import exchange.core2.rest.dto.SymbolSpecRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.concurrent.ExecutionException;

/**
 * REST controller for symbol (trading pair) management.
 *
 * ENDPOINTS:
 * - POST /api/symbols  - Create a new trading symbol
 *
 * USAGE EXAMPLES:
 *
 * Create BTC/LTC trading pair:
 *   curl -X POST http://localhost:8080/api/symbols \
 *     -H "Content-Type: application/json" \
 *     -d '{
 *       "symbolId":241,
 *       "type":"CURRENCY_EXCHANGE_PAIR",
 *       "baseCurrency":11,
 *       "quoteCurrency":15,
 *       "baseScaleK":1000000,
 *       "quoteScaleK":10000,
 *       "takerFee":1900,
 *       "makerFee":700
 *     }'
 *
 * SYMBOL PRICING EXAMPLE:
 * For BTC/LTC with above parameters:
 * - 1 lot = 1,000,000 satoshi = 0.01 BTC
 * - 1 price step = 10,000 litoshi
 * - Price of 15,400 means: 15,400 * 10,000 = 154,000,000 litoshi = 1.54 LTC per lot
 * - So 1 lot (0.01 BTC) costs 1.54 LTC
 * - Exchange rate: 154 LTC per 1 BTC
 */
@Slf4j
@RestController
@RequestMapping("/api/symbols")
@RequiredArgsConstructor
public class SymbolController {

    private final ExchangeApi exchangeApi;

    /**
     * Create a new trading symbol (trading pair).
     *
     * @param request Symbol specification
     * @return Success/failure response
     */
    @PostMapping
    public ApiResponse<String> createSymbol(@RequestBody SymbolSpecRequest request) {
        log.info("Creating symbol: symbolId={}, type={}, base={}, quote={}",
                request.getSymbolId(),
                request.getType(),
                request.getBaseCurrency(),
                request.getQuoteCurrency());

        try {
            // Build CoreSymbolSpecification
            CoreSymbolSpecification.CoreSymbolSpecificationBuilder builder = CoreSymbolSpecification.builder()
                    .symbolId(request.getSymbolId())
                    .type(request.getType())
                    .baseCurrency(request.getBaseCurrency())
                    .quoteCurrency(request.getQuoteCurrency())
                    .baseScaleK(request.getBaseScaleK())
                    .quoteScaleK(request.getQuoteScaleK())
                    .takerFee(request.getTakerFee())
                    .makerFee(request.getMakerFee());

            // Add optional fields for futures/options
            if (request.getMarginBuy() != null) {
                builder.marginBuy(request.getMarginBuy());
            }
            if (request.getMarginSell() != null) {
                builder.marginSell(request.getMarginSell());
            }

            CoreSymbolSpecification spec = builder.build();

            // Submit batch command (can add multiple symbols at once)
            CommandResultCode result = exchangeApi.submitBinaryDataAsync(
                    new BatchAddSymbolsCommand(spec)
            ).get();

            if (result == CommandResultCode.SUCCESS) {
                return ApiResponse.success(
                        "Symbol created successfully",
                        String.format("symbolId=%d, %s/%s",
                                request.getSymbolId(),
                                request.getBaseCurrency(),
                                request.getQuoteCurrency())
                );
            } else {
                return ApiResponse.error("Failed to create symbol", result.toString());
            }

        } catch (InterruptedException | ExecutionException e) {
            log.error("Error creating symbol symbolId={}", request.getSymbolId(), e);
            return ApiResponse.error("Error creating symbol", e.getMessage());
        }
    }
}
