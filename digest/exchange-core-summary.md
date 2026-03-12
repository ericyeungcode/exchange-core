https://github.com/exchange-core/exchange-core


```
⏺ Based on my exploration of the codebase, here's a comprehensive summary:

  Project Summary

  Exchange-Core is a high-performance, open-source market exchange matching engine designed for ultra-low latency trading operations. It can process millions of orders per second
   with sub-microsecond latencies.

  Key Capabilities

  - 3M users with 10M accounts
  - 100K order books (symbols) with 4M pending orders
  - <1ms latency for 1M+ ops/second
  - 150ns per match for large market orders
  - Supports 24/7 pauseless operation

  Core Features

  1. Order Matching Engine - Matches buy/sell orders using price-time priority
  2. Risk Control & Accounting - Validates user balances, margin requirements, and manages positions
  3. Event Sourcing - Disk journaling, snapshots, and replay capability
  4. Multi-Mode Trading - Direct exchange (spot) and margin trading
  5. Low GC Pressure - Lock-free algorithms, object pooling, single ring buffer

  Technology Stack

  - LMAX Disruptor - Lock-free ring buffer for inter-thread communication
  - Eclipse Collections - High-performance collections
  - Agrona - Low-latency data structures
  - Chronicle-Wire - Serialization
  - Adaptive Radix Trees - Order book implementation

  ---
  Data Flow Architecture

  ┌─────────────────────────────────────────────────────────────────┐
  │                        CLIENT API LAYER                          │
  │  (ExchangeApi - Submit orders, queries, commands via gateway)   │
  └───────────────────────────┬─────────────────────────────────────┘
                              │
                              ▼
  ┌─────────────────────────────────────────────────────────────────┐
  │                    LMAX DISRUPTOR RING BUFFER                    │
  │           (OrderCommand objects - Single point of entry)         │
  └───────────────────────────┬─────────────────────────────────────┘
                              │
                              ▼
  ┌─────────────────────────────────────────────────────────────────┐
  │  STAGE 1: GROUPING PROCESSOR (G)                                 │
  │  - Groups commands by correlation ID                             │
  │  - Prepares commands for parallel processing                     │
  └──────────────┬──────────────────────────────────────────────────┘
                 │
                 ├────────────┬────────────────────────────┐
                 │            │                            │
                 ▼            ▼                            ▼
  ┌──────────────────┐ ┌───────────────────┐  ┌─────────────────────┐
  │  STAGE 2a:       │ │  STAGE 2b:        │  │  STAGE 2c:          │
  │  JOURNALING (J)  │ │  RISK ENGINE (R1) │  │  (Optional)         │
  │  (Optional)      │ │  Pre-Processing   │  │  More Risk Shards   │
  │                  │ │                   │  │                     │
  │  - Write to      │ │  • User auth      │  │  Sharded by UID     │
  │    disk journal  │ │  • Balance check  │  │  for scalability    │
  │  - LZ4 compress  │ │  • Margin check   │  │                     │
  │  - Event source  │ │  • Risk approval  │  │                     │
  │  - Replay on     │ │                   │  │                     │
  │    restart       │ │  Sharded by UID   │  │                     │
  └──────────────────┘ └─────────┬─────────┘  └─────────┬───────────┘
                                 │                       │
                                 └───────┬───────────────┘
                                         ▼
                ┌────────────────────────────────────────────────────┐
                │  STAGE 3: MATCHING ENGINE ROUTER (ME)              │
                │                                                    │
                │  ┌──────────────────────────────────────────┐    │
                │  │   Order Books (by Symbol)                 │    │
                │  │   - Direct or Naive implementation        │    │
                │  │   - Price-time priority matching          │    │
                │  │   - Generates MatcherTradeEvents          │    │
                │  │                                           │    │
                │  │   Supports:                               │    │
                │  │   • GTC (Good-Till-Cancel)                │    │
                │  │   • IOC (Immediate-or-Cancel)             │    │
                │  │   • FOK-B (Fill-or-Kill Budget)           │    │
                │  └──────────────────────────────────────────┘    │
                │                                                    │
                │  Sharded by Symbol ID for horizontal scaling      │
                └─────────────────────┬──────────────────────────────┘
                                      │
                                      ▼
                ┌────────────────────────────────────────────────────┐
                │  STAGE 4: RISK ENGINE (R2) - Release               │
                │                                                    │
                │  • Process trade events from matching              │
                │  • Update user balances                            │
                │  • Release held funds                              │
                │  • Calculate fees                                  │
                │  • Update positions (margin mode)                  │
                │  • Update last price cache                         │
                │                                                    │
                │  Sharded by UID                                    │
                └─────────────────────┬──────────────────────────────┘
                                      │
                                      ▼
                ┌────────────────────────────────────────────────────┐
                │  STAGE 5: RESULTS HANDLER (E)                      │
                │                                                    │
                │  • Publish results to client                       │
                │  • Fire events (TradeEvent, RejectEvent, etc.)     │
                │  • Generate L2 market data snapshots               │
                │  • Process reports (balances, positions)           │
                └─────────────────────┬──────────────────────────────┘
                                      │
                                      ▼
                ┌────────────────────────────────────────────────────┐
                │         EVENTS CONSUMER / RESULTS API              │
                │  (User-provided handler receives trade results)    │
                └────────────────────────────────────────────────────┘

  Data Flow Details

  1. Order Command Flow:
  User Order → API → RingBuffer → Grouping → Risk Check (R1) →
  Matching Engine → Risk Release (R2) → Results → User

  2. Key Data Structures:
  - OrderCommand - Travels through the pipeline, modified at each stage
  - MatcherTradeEvent - Chain of trade events attached to OrderCommand
  - L2MarketData - Market depth snapshot (optional)
  - UserProfile - User accounts and positions
  - IOrderBook - Symbol-specific order book (bids/asks)

  3. Sharding Strategy:
  - Risk Engines: Sharded by UID % numShards (user-based)
  - Matching Engines: Sharded by SymbolID % numShards (symbol-based)
  - Enables horizontal scaling and parallel processing

  4. Processing Modes:
  - Exchange Mode: Spot trading, full balance hold
  - Margin Mode: Futures contracts, partial margin requirement

  5. Persistence:
  - Journaling: Every command written to disk (optional)
  - Snapshots: Full state serialization for fast recovery
  - Replay: Rebuild state from journal on startup

  This architecture achieves ultra-low latency through lock-free algorithms, cache-friendly design, and pipelined multi-core processing.


```