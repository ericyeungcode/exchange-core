
```

  Overview

  ExchangeCore uses LMAX Disruptor 3.4.4 as its core inter-thread communication framework for ultra-low latency, high-throughput order processing. The disruptor replaces
  traditional queue-based approaches with a lock-free ring buffer architecture.

  Core Architecture

  1. Ring Buffer Setup (ExchangeCore.java:92-97)

  Disruptor<OrderCommand> disruptor = new Disruptor<>(
      OrderCommand::new,
      ringBufferSize,           // Power of 2 (default: 16K)
      threadFactory,
      ProducerType.MULTI,       // Multiple gateway threads
      waitStrategy              // Configurable wait strategy
  );

  2. Event Processing Pipeline

  The disruptor implements a multi-stage event processing pipeline:

  Publishers → [Ring Buffer] → G → (J) ∥ R1 → ME → R2 → E

  Stages:
  - G (Grouping): Groups commands into batches for efficient processing
  - J (Journaling): Optional persistence (runs in parallel with R1+ME)
  - R1 (Risk Hold): Pre-processing risk checks (TwoStepMasterProcessor)
  - ME (Matching Engine): Order matching logic (multiple shards possible)
  - R2 (Risk Release): Post-processing risk release (TwoStepSlaveProcessor)
  - E (Results): Results handler that publishes to consumers

  3. Key Components

  GroupingProcessor (GroupingProcessor.java)

  - First stage after ring buffer
  - Groups events by eventsGroup counter
  - Controls batching with:
    - msgsInGroupLimit: Max messages per group (default: 256)
    - maxGroupDurationNs: Max time before switching groups (default: 10μs)
  - Triggers L2 market data updates every 10ms
  - Handles event pooling for trade events

  TwoStepMasterProcessor (Risk R1)

  - Implements risk pre-processing
  - Uses master-slave coordination pattern
  - Processes events in groups, then signals slave processor
  - Implements custom wait-spin logic

  TwoStepSlaveProcessor (Risk R2)

  - Completes risk processing after matching engine
  - Waits for master processor signal via handlingCycle()
  - No blocking wait strategy (SECOND_STEP_NO_WAIT)

  ResultsHandler

  - Final stage that delivers results to consumers
  - Can be enabled/disabled via GROUPING_CONTROL commands

  4. Command Publishing (ExchangeApi.java)

  Commands are published to the ring buffer using EventTranslators:

  // Synchronous
  ringBuffer.publishEvent(NEW_ORDER_TRANSLATOR, apiCommand);

  // Asynchronous with promise
  ringBuffer.publishEvent((cmd, seq, apiCmd) -> {
      translator.translateTo(cmd, seq, apiCmd);
      promises.put(seq, callback);
  }, apiCommand);

  Key methods:
  - submitCommand(): Fire-and-forget
  - submitCommandAsync(): Returns CompletableFuture
  - submitCommandsSync(): Batch submission with sync on last

  5. Wait Strategies (CoreWaitStrategy.java)

  Three strategies for different performance profiles:

  ┌───────────┬─────────┬───────────┬────────────────────┐
  │ Strategy  │ Latency │ CPU Usage │      Use Case      │
  ├───────────┼─────────┼───────────┼────────────────────┤
  │ BUSY_SPIN │ Lowest  │ Highest   │ Ultra-low latency  │
  ├───────────┼─────────┼───────────┼────────────────────┤
  │ YIELDING  │ Low     │ Medium    │ Balanced           │
  ├───────────┼─────────┼───────────┼────────────────────┤
  │ BLOCKING  │ Higher  │ Lowest    │ Throughput-focused │
  └───────────┴─────────┴───────────┴────────────────────┘

  6. Performance Configurations

  Latency-Optimized (PerformanceConfiguration.java:132)

  - Ring buffer: 2K
  - Wait strategy: BUSY_SPIN
  - Thread affinity: Enabled
  - 1 matching + 1 risk engine

  Throughput-Optimized (PerformanceConfiguration.java:148)

  - Ring buffer: 64K
  - Wait strategy: BUSY_SPIN
  - 4 matching + 2 risk engines
  - Higher group limits (4096 msgs, 4ms)

  7. Exception Handling (DisruptorExceptionHandler.java)

  Custom exception handler that:
  - Logs exceptions with sequence numbers
  - Publishes SHUTDOWN_SIGNAL on fatal errors
  - Gracefully shuts down the disruptor

  8. Key Features

  Scalability:
  - Configurable number of matching engines (sharding by symbol)
  - Configurable number of risk engines
  - Independent processors run on dedicated threads

  Low Latency:
  - Lock-free ring buffer (no contention)
  - CPU cache-friendly design
  - Optional thread affinity binding
  - Busy-spin wait strategies

  Reliability:
  - Optional journaling for event sourcing
  - Graceful shutdown with sequence alignment
  - Exception handling and recovery

  Batching & Grouping:
  - Automatic grouping for efficiency
  - Configurable batch sizes
  - Time-based and size-based triggers

  9. Threading Model

  - Publishers: Multiple gateway threads (ProducerType.MULTI)
  - Grouping: 1 dedicated thread
  - Risk R1: N threads (one per risk engine)
  - Matching: M threads (one per matching engine)
  - Risk R2: N threads (coordinated with R1)
  - Journaling: 1 thread (if enabled)
  - Results: 1 thread

  Benefits

  1. Ultra-low latency: Typical latencies in microseconds
  2. High throughput: Millions of operations per second
  3. Predictable performance: No GC pressure, mechanical sympathy
  4. Lock-free: No thread contention
  5. Scalable: Horizontal scaling with multiple processors
  6. Fault-tolerant: Optional journaling and graceful shutdown

  The disruptor is the heart of ExchangeCore's performance, enabling it to compete with professional exchange systems while maintaining low latency and high reliability.


```