package exchange.core2.rest.dto;

import lombok.Data;

/**
 * Request DTO for adjusting user balance (deposit/withdrawal).
 *
 * EXAMPLE - Deposit:
 * POST /api/users/{uid}/balance
 * {
 *   "currency": 15,
 *   "amount": 2000000000,
 *   "transactionId": 1001
 * }
 *
 * EXAMPLE - Withdrawal:
 * POST /api/users/{uid}/balance
 * {
 *   "currency": 15,
 *   "amount": -1000000000,
 *   "transactionId": 1002
 * }
 */
@Data
public class AdjustBalanceRequest {

    /**
     * Currency code.
     */
    private int currency;

    /**
     * Amount to add/subtract.
     * Positive: deposit
     * Negative: withdrawal
     *
     * Units depend on currency scale (e.g., satoshis for BTC).
     */
    private long amount;

    /**
     * Unique transaction ID.
     * Used to prevent duplicate deposits/withdrawals.
     */
    private long transactionId;
}
