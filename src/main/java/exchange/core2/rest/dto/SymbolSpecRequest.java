package exchange.core2.rest.dto;

import exchange.core2.core.common.SymbolType;
import lombok.Data;

/**
 * Request DTO for creating a trading symbol (trading pair).
 *
 * EXAMPLE - Currency Exchange Pair (BTC/LTC):
 * POST /api/symbols
 * {
 *   "symbolId": 241,
 *   "type": "CURRENCY_EXCHANGE_PAIR",
 *   "baseCurrency": 11,
 *   "quoteCurrency": 15,
 *   "baseScaleK": 1000000,
 *   "quoteScaleK": 10000,
 *   "takerFee": 1900,
 *   "makerFee": 700
 * }
 */
@Data
public class SymbolSpecRequest {

    /**
     * Unique symbol ID.
     */
    private int symbolId;

    /**
     * Symbol type:
     * - CURRENCY_EXCHANGE_PAIR: Spot trading (e.g., BTC/USD)
     * - FUTURES_CONTRACT: Futures contract
     * - OPTION: Option contract
     */
    private SymbolType type;

    /**
     * Base currency code (what is being traded).
     * Example: BTC in BTC/USD pair
     */
    private int baseCurrency;

    /**
     * Quote currency code (what you pay with).
     * Example: USD in BTC/USD pair
     */
    private int quoteCurrency;

    /**
     * Base scale factor (1 lot = baseScaleK units of base currency).
     * Example: 1_000_000 means 1 lot = 1M satoshis = 0.01 BTC
     */
    private long baseScaleK;

    /**
     * Quote scale factor (1 price step = quoteScaleK units of quote currency).
     * Example: 10_000 means 1 price step = 10K litoshis
     */
    private long quoteScaleK;

    /**
     * Taker fee (charged to taker per lot traded).
     * In quote currency units.
     */
    private long takerFee;

    /**
     * Maker fee (charged to maker per lot traded).
     * In quote currency units.
     * Can be negative (rebate).
     */
    private long makerFee;

    /**
     * Margin buy (for futures/options, optional).
     */
    private Long marginBuy;

    /**
     * Margin sell (for futures/options, optional).
     */
    private Long marginSell;
}
