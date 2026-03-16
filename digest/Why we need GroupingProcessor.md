❯ Why we need GroupingProcessor?

⏺ Great question! The GroupingProcessor serves several critical purposes in the ExchangeCore architecture. Let me explain:

  Primary Purpose: Pipeline Synchronization

  The GroupingProcessor is the coordinator between the two-phase risk processing stages (R1 and R2). Here's how it works:

⏺ Read 1 file (ctrl+o to expand)

⏺ Key Reasons We Need GroupingProcessor

  1. Two-Phase Pipeline Coordination 🔄

  The GroupingProcessor assigns an eventsGroup counter to each command. When R1 (TwoStepMasterProcessor) sees the group number change, it triggers R2 to process the previous
  group:

  Time:  T0      T1      T2      T3      T4
  G:     [---Group 1---][---Group 2---][---Group 3---]
  R1:    [Process G1   ][Process G2   ][Process G3   ]
                         ↓ trigger R2
  R2:                   [Process G1   ][Process G2   ]

  Without grouping, R2 would need to process every single event individually, causing:
  - Excessive synchronization overhead
  - Context switching between R1 and R2
  - Poor cache locality

  2. Latency vs Throughput Control ⚖️

  GroupingProcessor provides tunable batching parameters:

  // GroupingProcessor.java:66-67
  msgsInGroupLimit = 256;        // Max messages per group
  maxGroupDurationNs = 10_000;   // Max 10μs before forcing group switch

  Small groups (latency-optimized):
  - Groups of 256 messages or 10μs
  - Events processed quickly
  - Lower mean latency

  Large groups (throughput-optimized):
  - Groups of 4096 messages or 4ms
  - Better batching efficiency
  - Higher throughput, better tail latency

⏺ Read 1 file (ctrl+o to expand)

⏺ 3. Prevents R2 Starvation 🚫

  Notice lines 229-234: Time-based group switching. Even if messages arrive slowly, the group switches after maxGroupDurationNs to ensure R2 doesn't wait forever:

  if (msgsInGroup > 0 && t > groupLastNs) {
      // Force group switch after timeout
      groupCounter++;
      msgsInGroup = 0;
  }

  Without this, in low-traffic scenarios, R2 would starve waiting for a full batch.

  4. Backpressure Control 🎛️

  Looking at the configuration validation:

⏺ Read 1 file (ctrl+o to expand)

⏺ Group size must be < 1/4 of ring buffer size. This ensures:
  - R2 can keep up with R1
  - Prevents ring buffer overflow
  - Maximum of 4 groups "in flight" at once

  5. Event Cleanup & Resource Management 🧹

⏺ Read 1 file (ctrl+o to expand)

⏺ GroupingProcessor collects and pools trade events to reduce garbage collection:
  - Collects MatcherTradeEvent chains
  - Returns them to SharedPool when chain reaches target length
  - Clears marketData references
  - Result: Reduced GC pressure in high-frequency trading

  6. L2 Market Data Scheduling 📊

⏺ Read 1 file (ctrl+o to expand)

⏺ GroupingProcessor triggers L2 market data updates every 10ms by setting serviceFlags = 1. This is essential for:
  - Risk processors to evaluate PnL
  - Market data consumers to get order book snapshots
  - Without it, downstream systems would have stale data

  7. Special Command Handling ⚠️

⏺ Read 1 file (ctrl+o to expand)

⏺ Forces immediate group switch for critical commands:
  - RESET - Clear all state
  - PERSIST_STATE_MATCHING - Snapshot creation
  - GROUPING_CONTROL - Enable/disable processing
  - Binary data (last fragment)

  This ensures pipeline consistency - these commands are fully processed through R1→ME→R2 before the next group starts.

  ---
  Summary: Why GroupingProcessor is Essential

  ┌───────────────────────────┬──────────────────────────────────────────┬──────────────────────────────────────────────────────────────┐
  │          Purpose          │                 Benefit                  │                          Without It                          │
  ├───────────────────────────┼──────────────────────────────────────────┼──────────────────────────────────────────────────────────────┤
  │ Pipeline sync             │ R1 and R2 process in parallel batches    │ R2 would process every event individually = massive overhead │
  ├───────────────────────────┼──────────────────────────────────────────┼──────────────────────────────────────────────────────────────┤
  │ Batching control          │ Configurable latency/throughput tradeoff │ No way to tune performance characteristics                   │
  ├───────────────────────────┼──────────────────────────────────────────┼──────────────────────────────────────────────────────────────┤
  │ Time-based flushing       │ Prevents starvation in low traffic       │ R2 could wait indefinitely                                   │
  ├───────────────────────────┼──────────────────────────────────────────┼──────────────────────────────────────────────────────────────┤
  │ Backpressure              │ Prevents R2 from falling too far behind  │ Ring buffer overflow risk                                    │
  ├───────────────────────────┼──────────────────────────────────────────┼──────────────────────────────────────────────────────────────┤
  │ Event pooling             │ Reduced GC pressure                      │ Excessive garbage collection                                 │
  ├───────────────────────────┼──────────────────────────────────────────┼──────────────────────────────────────────────────────────────┤
  │ L2 scheduling             │ Periodic market data updates             │ Stale market data                                            │
  ├───────────────────────────┼──────────────────────────────────────────┼──────────────────────────────────────────────────────────────┤
  │ Critical command handling │ Ensures pipeline consistency             │ State corruption risk                                        │
  └───────────────────────────┴──────────────────────────────────────────┴──────────────────────────────────────────────────────────────┘

  Bottom line: GroupingProcessor is the intelligent traffic controller that makes the multi-stage pipeline efficient, predictable, and tunable. Without it, you'd have either:
  - Excessive overhead from processing events one-by-one
  - Unpredictable latency from uncontrolled batching
  - Resource leaks from poor event management

  It's a key architectural component that enables ExchangeCore to achieve microsecond latencies at millions of ops/second! 🚀
