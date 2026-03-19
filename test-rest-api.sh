#!/bin/bash

# Exchange Core REST API Test Script
# This script demonstrates the complete workflow of the REST API

set -e  # Exit on error

BASE_URL="http://localhost:8080"

echo "=================================="
echo "Exchange Core REST API Test Script"
echo "=================================="
echo ""

# Color codes for output
GREEN='\033[0;32m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

function print_step() {
    echo -e "${BLUE}[$1]${NC} $2"
}

function print_success() {
    echo -e "${GREEN}✓${NC} $1"
}

# Check if server is running
print_step "0" "Checking if server is running..."
if ! curl -s "$BASE_URL/health" > /dev/null; then
    echo "Error: Server is not running at $BASE_URL"
    echo "Please start the server with: mvn spring-boot:run -Dspring-boot.run.skip=false"
    exit 1
fi
print_success "Server is running"
echo ""

# Step 1: Create symbol
print_step "1" "Creating BTC/LTC trading symbol (symbolId=241)..."
curl -s -X POST "$BASE_URL/api/symbols" \
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
  }' | jq '.'
print_success "Symbol created"
echo ""

# Step 2: Create users
print_step "2" "Creating user 301..."
curl -s -X POST "$BASE_URL/api/users?uid=301" | jq '.'
print_success "User 301 created"
echo ""

print_step "3" "Creating user 302..."
curl -s -X POST "$BASE_URL/api/users?uid=302" | jq '.'
print_success "User 302 created"
echo ""

# Step 3: Deposit funds
print_step "4" "User 301 deposits 20 LTC (2,000,000,000 litoshis)..."
curl -s -X POST "$BASE_URL/api/users/301/balance" \
  -H "Content-Type: application/json" \
  -d '{
    "currency":15,
    "amount":2000000000,
    "transactionId":1001
  }' | jq '.'
print_success "Deposit successful"
echo ""

print_step "5" "User 302 deposits 0.10 BTC (10,000,000 satoshis)..."
curl -s -X POST "$BASE_URL/api/users/302/balance" \
  -H "Content-Type: application/json" \
  -d '{
    "currency":11,
    "amount":10000000,
    "transactionId":1002
  }' | jq '.'
print_success "Deposit successful"
echo ""

# Step 4: Place orders
print_step "6" "User 301 places BID order (buy 12 lots @ 15400)..."
curl -s -X POST "$BASE_URL/api/orders" \
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
  }' | jq '.'
print_success "BID order placed"
echo ""

print_step "7" "User 302 places ASK order (sell 10 lots @ 15250) - will match!"
curl -s -X POST "$BASE_URL/api/orders" \
  -H "Content-Type: application/json" \
  -d '{
    "uid":302,
    "orderId":5002,
    "symbol":241,
    "price":15250,
    "size":10,
    "action":"ASK",
    "orderType":"IOC"
  }' | jq '.'
print_success "ASK order placed (trade executed!)"
echo ""

# Step 5: Query order book
print_step "8" "Checking order book (should have 2 remaining lots)..."
curl -s "$BASE_URL/api/orderbook/241?depth=10" | jq '.'
print_success "Order book retrieved"
echo ""

# Step 6: Check balances
print_step "9" "Checking user 301 balance (should have BTC now)..."
curl -s "$BASE_URL/api/users/301/report" | jq '.data.accounts'
print_success "User 301 report retrieved"
echo ""

print_step "10" "Checking user 302 balance (should have LTC now)..."
curl -s "$BASE_URL/api/users/302/report" | jq '.data.accounts'
print_success "User 302 report retrieved"
echo ""

# Step 7: Cancel remaining order
print_step "11" "Cancelling user 301's remaining order..."
curl -s -X DELETE "$BASE_URL/api/orders/5001?uid=301&symbol=241" | jq '.'
print_success "Order cancelled"
echo ""

# Step 8: Final balances
print_step "12" "Getting total balances (reconciliation)..."
curl -s "$BASE_URL/api/reports/balances" | jq '.'
print_success "Total balances retrieved"
echo ""

echo "=================================="
echo -e "${GREEN}All tests completed successfully!${NC}"
echo "=================================="
echo ""
echo "Check the server console logs to see trade events, reduce events, etc."
