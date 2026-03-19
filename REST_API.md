# Exchange Core REST API

A Spring Boot REST API wrapper for the Exchange Core high-performance matching engine.

## Quick Start

### 1. Build the project
```bash
mvn clean package -DskipTests
```

### 2. Run the REST API server
```bash
mvn spring-boot:run -Dspring-boot.run.skip=false
```

Or run directly with Java:
```bash
java -cp target/exchange-core-0.5.4-SNAPSHOT.jar \
  -Dloader.main=exchange.core2.rest.ExchangeCoreApplication \
  org.springframework.boot.loader.PropertiesLauncher
```

### 3. Access the API
- Base URL: `http://localhost:8080`
- Default port: `8080` (configurable in `application.properties`)

## Architecture Overview

```
HTTP Clients
    ↓
Spring Boot REST Controllers
    ↓
ExchangeApi (facade)
    ↓
Disruptor RingBuffer
    ↓
Exchange Core Pipeline (G → J/R1 → ME → R2 → E)
```

The REST API submits commands asynchronously to ExchangeCore via the Disruptor RingBuffer, waits for results, and returns them as HTTP responses.

## API Endpoints

### User Management

#### Create User
```bash
POST /api/users?uid={uid}

# Example
curl -X POST "http://localhost:8080/api/users?uid=301"
```

#### Deposit Funds
```bash
POST /api/users/{uid}/balance
Content-Type: application/json

{
  "currency": 15,
  "amount": 2000000000,
  "transactionId": 1001
}

# Example
curl -X POST http://localhost:8080/api/users/301/balance \
  -H "Content-Type: application/json" \
  -d '{"currency":15,"amount":2000000000,"transactionId":1001}'
```

#### Withdraw Funds
```bash
POST /api/users/{uid}/balance
Content-Type: application/json

{
  "currency": 15,
  "amount": -1000000000,
  "transactionId": 1002
}

# Example - withdraw 1 billion units
curl -X POST http://localhost:8080/api/users/301/balance \
  -H "Content-Type: application/json" \
  -d '{"currency":15,"amount":-1000000000,"transactionId":1002}'
```

#### Suspend User
```bash
POST /api/users/{uid}/suspend

# Example
curl -X POST http://localhost:8080/api/users/301/suspend
```

#### Resume User
```bash
POST /api/users/{uid}/resume

# Example
curl -X POST http://localhost:8080/api/users/301/resume
```

---

### Symbol Management

#### Create Trading Symbol
```bash
POST /api/symbols
Content-Type: application/json

{
  "symbolId": 241,
  "type": "CURRENCY_EXCHANGE_PAIR",
  "baseCurrency": 11,
  "quoteCurrency": 15,
  "baseScaleK": 1000000,
  "quoteScaleK": 10000,
  "takerFee": 1900,
  "makerFee": 700
}

# Example - Create BTC/LTC trading pair
curl -X POST http://localhost:8080/api/symbols \
  -H "Content-Type: application/json" \
  -d '{
    "symbolId":241,
    "type":"CURRENCY_EXCHANGE_PAIR",
    "baseCurrency":11,
    "quoteCurrency":15,
    "baseScaleK":1000000,
    "quoteScaleK":10000,
    "takerFee":1900,
    "makerFee":700
  }'
```

**Symbol Parameters Explained:**
- `symbolId`: Unique identifier for this trading pair
- `type`: `CURRENCY_EXCHANGE_PAIR`, `FUTURES_CONTRACT`, or `OPTION`
- `baseCurrency`: Currency code for the asset being traded (e.g., BTC = 11)
- `quoteCurrency`: Currency code for payment (e.g., LTC = 15)
- `baseScaleK`: 1 lot = baseScaleK units of base currency
  - Example: 1,000,000 means 1 lot = 1M satoshis = 0.01 BTC
- `quoteScaleK`: 1 price step = quoteScaleK units of quote currency
  - Example: 10,000 means 1 price step = 10K litoshis
- `takerFee`: Fee charged to taker (in quote currency units per lot)
- `makerFee`: Fee charged to maker (can be negative for rebates)

**Pricing Example:**
With the above BTC/LTC parameters:
- Price of `15400` means: 15,400 × 10,000 = 154,000,000 litoshis = 1.54 LTC per lot
- 1 lot (0.01 BTC) costs 1.54 LTC
- Exchange rate: 154 LTC per 1 BTC

---

### Order Management

#### Place Order
```bash
POST /api/orders
Content-Type: application/json

{
  "uid": 301,
  "orderId": 5001,
  "symbol": 241,
  "price": 15400,
  "size": 12,
  "action": "BID",
  "orderType": "GTC",
  "reservePrice": 15600
}

# Example - Place BID (buy) order
curl -X POST http://localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -d '{
    "uid":301,
    "orderId":5001,
    "symbol":241,
    "price":15400,
    "size":12,
    "action":"BID",
    "orderType":"GTC",
    "reservePrice":15600
  }'

# Example - Place ASK (sell) order
curl -X POST http://localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -d '{
    "uid":302,
    "orderId":5002,
    "symbol":241,
    "price":15250,
    "size":10,
    "action":"ASK",
    "orderType":"IOC"
  }'
```

**Order Parameters:**
- `uid`: User ID placing the order
- `orderId`: Unique order ID (client-generated, must be unique per user)
- `symbol`: Symbol ID (trading pair)
- `price`: Order price in price steps
- `size`: Order size in lots
- `action`: `BID` (buy) or `ASK` (sell)
- `orderType`:
  - `GTC` (Good-Till-Cancel): Stays in order book until filled or cancelled
  - `IOC` (Immediate-Or-Cancel): Fills immediately, cancels unfilled portion
  - `FOK` (Fill-Or-Kill): Fills completely or cancels entirely
  - `FOK_BUDGET`: FOK with budget limit
- `reservePrice` (optional): For BID, can move order up to this price; for ASK, can move down

#### Cancel Order
```bash
DELETE /api/orders/{orderId}?uid={uid}&symbol={symbol}

# Example
curl -X DELETE "http://localhost:8080/api/orders/5001?uid=301&symbol=241"
```

#### Move Order
```bash
PUT /api/orders/{orderId}/move?uid={uid}&symbol={symbol}&newPrice={newPrice}

# Example - Move order to new price level
curl -X PUT "http://localhost:8080/api/orders/5001/move?uid=301&symbol=241&newPrice=15300"
```

#### Reduce Order Size
```bash
PUT /api/orders/{orderId}/reduce?uid={uid}&symbol={symbol}&reduceBy={reduceBy}

# Example - Reduce order size by 5 lots
curl -X PUT "http://localhost:8080/api/orders/5001/reduce?uid=301&symbol=241&reduceBy=5"
```

---

### Queries and Reports

#### Get Order Book
```bash
GET /api/orderbook/{symbol}?depth={depth}

# Example - Get top 10 price levels for symbol 241
curl http://localhost:8080/api/orderbook/241?depth=10
```

#### Get User Report
```bash
GET /api/users/{uid}/report

# Example - Get account balance and positions for user 301
curl http://localhost:8080/api/users/301/report
```

**Response includes:**
- Account balances per currency
- Open positions
- Active orders

#### Get Total Balances
```bash
GET /api/reports/balances

# Example - Get total balances across all users (for reconciliation)
curl http://localhost:8080/api/reports/balances
```

#### Get State Hash
```bash
GET /api/reports/state-hash

# Example - Get hash representing complete exchange state
curl http://localhost:8080/api/reports/state-hash
```

---

## Complete Example Workflow

```bash
# 1. Create BTC/LTC symbol
curl -X POST http://localhost:8080/api/symbols \
  -H "Content-Type: application/json" \
  -d '{"symbolId":241,"type":"CURRENCY_EXCHANGE_PAIR","baseCurrency":11,"quoteCurrency":15,"baseScaleK":1000000,"quoteScaleK":10000,"takerFee":1900,"makerFee":700}'

# 2. Create two users
curl -X POST "http://localhost:8080/api/users?uid=301"
curl -X POST "http://localhost:8080/api/users?uid=302"

# 3. User 301 deposits 20 LTC
curl -X POST http://localhost:8080/api/users/301/balance \
  -H "Content-Type: application/json" \
  -d '{"currency":15,"amount":2000000000,"transactionId":1001}'

# 4. User 302 deposits 0.10 BTC
curl -X POST http://localhost:8080/api/users/302/balance \
  -H "Content-Type: application/json" \
  -d '{"currency":11,"amount":10000000,"transactionId":1002}'

# 5. User 301 places BID order (wants to buy BTC)
curl -X POST http://localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -d '{"uid":301,"orderId":5001,"symbol":241,"price":15400,"size":12,"action":"BID","orderType":"GTC","reservePrice":15600}'

# 6. User 302 places ASK order (wants to sell BTC) - will match!
curl -X POST http://localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -d '{"uid":302,"orderId":5002,"symbol":241,"price":15250,"size":10,"action":"ASK","orderType":"IOC"}'

# 7. Check order book
curl http://localhost:8080/api/orderbook/241?depth=10

# 8. Check user 301's balance
curl http://localhost:8080/api/users/301/report

# 9. Check user 302's balance
curl http://localhost:8080/api/users/302/report
```

---

## Response Format

All endpoints return a standard JSON response:

### Success Response
```json
{
  "success": true,
  "message": "Operation completed successfully",
  "data": { /* response data */ }
}
```

### Error Response
```json
{
  "success": false,
  "message": "Operation failed",
  "error": "Error details here"
}
```

---

## Configuration

Edit `src/main/resources/application.properties`:

```properties
# Server port
server.port=8080

# Logging level
logging.level.exchange.core2=DEBUG

# Future: Exchange Core configuration
# exchange.core.ring-buffer-size=8192
# exchange.core.matching-engines-num=1
# exchange.core.risk-engines-num=1
```

---

## Event Logging

The application logs all exchange events to the console:
- **Trade events**: Successful order matches
- **Reduce events**: Partial fills
- **Reject events**: Failed orders

Check the console output to see real-time trading activity.

---

## Architecture Details

### Thread Model
- **HTTP Threads**: Spring Boot's embedded Tomcat handles HTTP requests
- **Disruptor Threads**: Exchange Core runs on dedicated threads:
  - 1 × GroupingProcessor
  - 1 × JournalingHandler (if enabled)
  - N × RiskEngines (R1 and R2)
  - M × MatchingEngines
  - 1 × ResultsHandler

### Concurrency
- Multiple HTTP requests can submit orders concurrently
- Disruptor handles synchronization internally (lock-free)
- Results are returned via `CompletableFuture`

### Performance Considerations
- REST API adds latency compared to direct ExchangeApi usage
- Suitable for: web applications, mobile apps, external integrations
- Not suitable for: ultra-low-latency HFT (use ExchangeApi directly)

---

## Future Enhancements

- WebSocket support for real-time market data streaming
- Authentication and authorization
- Rate limiting
- More granular configuration via `application.properties`
- Batch order operations
- Historical data queries
- Admin endpoints (persist state, reset, etc.)

---

## Troubleshooting

### Port already in use
Change the port in `application.properties`:
```properties
server.port=8081
```

### OutOfMemoryError
Increase JVM heap size:
```bash
java -Xmx4g -jar exchange-core-rest.jar
```

### Logging too verbose
Reduce logging level in `application.properties`:
```properties
logging.level.exchange.core2=INFO
```

---

## License

Same as Exchange Core: Apache License 2.0
