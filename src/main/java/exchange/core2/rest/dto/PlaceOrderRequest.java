package exchange.core2.rest.dto;

import exchange.core2.core.common.OrderAction;
import exchange.core2.core.common.OrderType;
import lombok.Data;

/**
 * Request DTO for placing an order.
 *
 * EXAMPLE:
 * POST /api/orders
 * {
 *   "uid": 301,
 *   "orderId": 5001,
 *   "symbol": 241,
 *   "price": 15400,
 *   "size": 12,
 *   "action": "BID",
 *   "orderType": "GTC",
 *   "reservePrice": 15600
 * }
 */
@Data
public class PlaceOrderRequest {

    /**
     * User ID who is placing the order.
     */
    private long uid;

    /**
     * Unique order ID (client-generated).
     * Must be unique for this user.
     */
    private long orderId;

    /**
     * Symbol ID (trading pair).
     */
    private int symbol;

    /**
     * Order price in price steps.
     * For BID: maximum price willing to pay
     * For ASK: minimum price willing to accept
     */
    private long price;

    /**
     * Order size in lots.
     */
    private long size;

    /**
     * Order action: BID (buy) or ASK (sell).
     */
    private OrderAction action;

    /**
     * Order type:
     * - GTC (Good-Till-Cancel): remains in order book until filled or cancelled
     * - IOC (Immediate-Or-Cancel): fills immediately, cancels remainder
     * - FOK (Fill-Or-Kill): fills completely or cancels entirely
     * - FOK_BUDGET: FOK with budget limit
     */
    private OrderType orderType;

    /**
     * Reserve price (optional).
     * For BID: can move order up to this price without replacing
     * For ASK: can move order down to this price without replacing
     */
    private Long reservePrice;

    /**
     * User cookie (optional).
     * Application-specific data attached to the order.
     */
    private Integer userCookie;
}
