/*

 Key Sections Added:

  1. Class-level Overview

  - Explanation of the LMAX Disruptor framework (what it is, why it's fast)
  - Complete pipeline visualization showing all stages (G → J/R1 → ME → R2 → E)
  - Sharding concept for horizontal scaling

  2. Constructor Breakdown

  Each step is now clearly documented:

  - Step 1: Creating the Disruptor & RingBuffer
    - Why RingBuffer size must be power of 2
    - Different wait strategies (BusySpin, Yielding, Blocking)
    - ProducerType.MULTI for concurrent producers
  - Step 2: Shared Components
    - SerializationProcessor (journaling, snapshots, recovery)
    - SharedPool (object pooling to reduce GC)
    - Exception handling strategy
  - Step 3: Parallel Engine Initialization
    - Why CompletableFuture is used (startup time optimization)
    - MatchingEngineRouter vs RiskEngine responsibilities
    - Thread affinity for cache locality
  - Step 4: Pipeline Construction (Most detailed)
    - Disruptor syntax explained (handleEventsWith, after, groups)
    - ASCII diagram showing parallel vs sequential execution
    - Stage-by-stage breakdown:
        - Grouping (G): Pre-processes commands
      - Journaling (J) + Risk Hold (R1): Parallel execution explained
      - Matching Engines (ME): Trade execution after funds reserved
      - Risk Release (R2): Commit or release funds
      - Results Handler (E): Send results to clients
  - Step 5: Two-Step Processor Linking (R1 ↔ R2)
  - Step 6: Cleanup

  3. Runtime Methods

  - startup(): Threading model after start, crash recovery process
  - shutdown(): Graceful termination with SHUTDOWN_SIGNAL
  - Helper methods explained
  
*/

/*
 * Copyright 2019 Maksim Zheravin
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package exchange.core2.core;

import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.EventTranslator;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.TimeoutException;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.EventHandlerGroup;
import com.lmax.disruptor.dsl.ProducerType;
import exchange.core2.core.common.CoreWaitStrategy;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.cmd.OrderCommand;
import exchange.core2.core.common.cmd.OrderCommandType;
import exchange.core2.core.common.config.ExchangeConfiguration;
import exchange.core2.core.common.config.PerformanceConfiguration;
import exchange.core2.core.common.config.SerializationConfiguration;
import exchange.core2.core.orderbook.IOrderBook;
import exchange.core2.core.processors.*;
import exchange.core2.core.processors.journaling.ISerializationProcessor;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.ObjLongConsumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Main exchange core class.
 * Builds configuration and starts disruptor.
 *
 * ARCHITECTURE OVERVIEW:
 * =====================
 * This class uses the LMAX Disruptor framework - a high-performance inter-thread messaging library.
 *
 * WHAT IS THE DISRUPTOR?
 * - A lock-free, thread-safe queue (called a RingBuffer) for passing events between threads
 * - Much faster than traditional queues (LinkedBlockingQueue, etc) due to:
 *   1. Pre-allocated memory (no GC during runtime)
 *   2. Cache-line padding (avoids false sharing between CPU cores)
 *   3. Lock-free algorithms (uses CAS operations instead of locks)
 *   4. Sequential memory layout (CPU-friendly)
 *
 * HOW IT WORKS:
 * 1. Producers write OrderCommand events into the RingBuffer
 * 2. Consumers (EventHandlers) read and process these events in sequence
 * 3. Each consumer has its own sequence number tracking progress
 * 4. Consumers can be chained: consumer B waits for consumer A to finish each event
 * 5. Consumers can run in parallel: both read the same event after previous stage completes
 *
 * EXCHANGE PIPELINE STAGES:
 * =========================
 * [Producer Threads] → [RingBuffer] → [Event Processing Pipeline]:
 *
 *   1. [Grouping (G)] - Groups correlated commands together, prepares them for processing
 *        ↓
 *   2. [Journaling (J)] ⎫
 *      [Risk Hold (R1)]  ⎬ → Run in PARALLEL (independent of each other)
 *        ↓              ⎭
 *   3. [Matching Engine (ME)] - Executes orders against order books
 *        ↓
 *   4. [Risk Release (R2)] - Releases reserved funds/positions
 *        ↓
 *   5. [Results Handler (E)] - Sends results back to API clients
 *
 * SHARDING:
 * - Multiple MatchingEngines and RiskEngines run in parallel
 * - Each handles a subset of symbols (e.g., ME0: AAPL,GOOGL; ME1: MSFT,TSLA)
 * - This allows horizontal scaling across CPU cores
 */
@Slf4j
public final class ExchangeCore {

    // The Disruptor instance - manages the RingBuffer and event handlers
    private final Disruptor<OrderCommand> disruptor;

    // The RingBuffer - pre-allocated circular array of OrderCommand objects
    // Producers write events here, consumers read from it
    private final RingBuffer<OrderCommand> ringBuffer;

    // The API facade - provides synchronous/asynchronous methods for submitting orders
    private final ExchangeApi api;

    // Handles state snapshots and journal replay for crash recovery
    private final ISerializationProcessor serializationProcessor;

    private final ExchangeConfiguration exchangeConfiguration;

    // core can be started and stopped only once
    private boolean started = false;
    private boolean stopped = false;

    // enable MatcherTradeEvent pooling (object reuse to reduce GC pressure)
    public static final boolean EVENTS_POOLING = false;

    /**
     * Exchange core constructor.
     *
     * CONSTRUCTION PROCESS:
     * 1. Create the Disruptor and RingBuffer
     * 2. Create shared objects (thread pool, object pools, engines)
     * 3. Build the event processing pipeline (wire up event handlers)
     *
     *  @param resultsConsumer       - custom consumer of processed commands
     * @param exchangeConfiguration - exchange configuration
     */
    @Builder
    public ExchangeCore(final ObjLongConsumer<OrderCommand> resultsConsumer,
                        final ExchangeConfiguration exchangeConfiguration) {

        log.debug("Building exchange core from configuration: {}", exchangeConfiguration);

        this.exchangeConfiguration = exchangeConfiguration;

        final PerformanceConfiguration perfCfg = exchangeConfiguration.getPerformanceCfg();

        // ============================================================================
        // STEP 1: CREATE THE DISRUPTOR & RING BUFFER
        // ============================================================================

        // RingBuffer size MUST be a power of 2 (e.g., 1024, 2048, 4096, 8192)
        // This allows fast modulo operations using bitwise AND: index & (size - 1)
        final int ringBufferSize = perfCfg.getRingBufferSize();

        // ThreadFactory creates the consumer threads (one per EventHandler)
        // Typically uses thread affinity to pin threads to specific CPU cores
        final ThreadFactory threadFactory = perfCfg.getThreadFactory();

        // WaitStrategy determines how consumers wait for new events:
        // - BusySpinWaitStrategy: lowest latency, burns CPU (spins in tight loop)
        // - YieldingWaitStrategy: low latency, yields to other threads
        // - BlockingWaitStrategy: higher latency, saves CPU (uses locks)
        final CoreWaitStrategy coreWaitStrategy = perfCfg.getWaitStrategy();

        // Create the Disruptor with:
        // - OrderCommand::new - factory to pre-allocate all RingBuffer slots
        // - ringBufferSize - capacity of the ring buffer
        // - threadFactory - creates consumer threads
        // - ProducerType.MULTI - multiple threads can publish concurrently (vs SINGLE for one producer)
        // - waitStrategy - how consumers wait for events
        this.disruptor = new Disruptor<>(
                OrderCommand::new,
                ringBufferSize,
                threadFactory,
                ProducerType.MULTI, // multiple gateway threads are writing
                coreWaitStrategy.getDisruptorWaitStrategyFactory().get());

        // Get the RingBuffer reference for publishing events
        this.ringBuffer = disruptor.getRingBuffer();

        // ExchangeApi wraps the RingBuffer and provides user-facing methods
        // (submitCommand, submitCommandAsync, etc.)
        this.api = new ExchangeApi(ringBuffer, perfCfg.getBinaryCommandsLz4CompressorFactory().get());

        // ============================================================================
        // STEP 2: CREATE SHARED COMPONENTS
        // ============================================================================

        // Factory for creating OrderBook implementations (e.g., NaiveOrderBook, OptimizedOrderBook)
        final IOrderBook.OrderBookFactory orderBookFactory = perfCfg.getOrderBookFactory();

        // Number of shards for horizontal scaling
        // More shards = more parallelism, but requires careful symbol distribution
        final int matchingEnginesNum = perfCfg.getMatchingEnginesNum();
        final int riskEnginesNum = perfCfg.getRiskEnginesNum();

        final SerializationConfiguration serializationCfg = exchangeConfiguration.getSerializationCfg();

        // SerializationProcessor handles:
        // 1. Journaling - writing commands to disk for replay after crash
        // 2. Snapshots - saving complete exchange state periodically
        // 3. State restoration - loading state on startup
        serializationProcessor = serializationCfg.getSerializationProcessorFactory().apply(exchangeConfiguration);

        // SharedPool - object pool for reusing MatcherTradeEvent objects
        // Reduces GC pressure by recycling objects instead of allocating new ones
        // poolInitialSize: initial capacity per engine
        // chainLength: 1 = disabled (create new objects), 1024 = pooling enabled
        final int poolInitialSize = (matchingEnginesNum + riskEnginesNum) * 8;
        final int chainLength = EVENTS_POOLING ? 1024 : 1;
        final SharedPool sharedPool = new SharedPool(poolInitialSize * 4, poolInitialSize, chainLength);

        // Exception handler for Disruptor event processing errors
        // If any handler throws an exception:
        // 1. Log the error with sequence number
        // 2. Publish a SHUTDOWN_SIGNAL to gracefully stop all handlers
        // 3. Shutdown the disruptor
        // This prevents the system from continuing in an inconsistent state
        final DisruptorExceptionHandler<OrderCommand> exceptionHandler = new DisruptorExceptionHandler<>("main", (ex, seq) -> {
            log.error("Exception thrown on sequence={}", seq, ex);
            // TODO re-throw exception on publishing
            ringBuffer.publishEvent(SHUTDOWN_SIGNAL_TRANSLATOR);
            disruptor.shutdown();
        });

        disruptor.setDefaultExceptionHandler(exceptionHandler);

        // ============================================================================
        // STEP 3: PARALLEL INITIALIZATION OF ENGINES
        // ============================================================================
        // Creating engines can be slow (loading state from snapshots, initializing data structures)
        // We use CompletableFuture to create all engines in parallel, reducing startup time
        //
        // Example: If each engine takes 100ms to initialize:
        // - Sequential: 4 engines × 100ms = 400ms
        // - Parallel: max(100ms, 100ms, 100ms, 100ms) = 100ms
        //
        // The executor uses the same ThreadFactory as the disruptor to ensure:
        // - Thread affinity (threads run on same CPU socket as event handlers)
        // - Better cache locality
        // - Reduced memory latency
        final ExecutorService loaderExecutor = Executors.newFixedThreadPool(matchingEnginesNum + riskEnginesNum, threadFactory);

        // Start creating ALL matching engines in parallel
        // Each MatchingEngineRouter:
        // - Manages order books for a subset of symbols (shard)
        // - Routes commands to the appropriate order book
        // - Executes trades when orders match
        // Map key: shardId (0, 1, 2, ...), Map value: CompletableFuture that will contain the engine
        final Map<Integer, CompletableFuture<MatchingEngineRouter>> matchingEngineFutures = IntStream.range(0, matchingEnginesNum)
                .boxed()
                .collect(Collectors.toMap(
                        shardId -> shardId,
                        shardId -> CompletableFuture.supplyAsync(
                                () -> new MatchingEngineRouter(shardId, matchingEnginesNum, serializationProcessor, orderBookFactory, sharedPool, exchangeConfiguration),
                                loaderExecutor)));

        // TODO create processors in same thread we will execute it??

        // Start creating ALL risk engines in parallel (same pattern as matching engines)
        // Each RiskEngine:
        // - Validates orders (sufficient funds, position limits, etc.)
        // - Reserves funds/positions before matching (R1 - Risk Hold)
        // - Releases or commits funds after matching (R2 - Risk Release)
        final Map<Integer, CompletableFuture<RiskEngine>> riskEngineFutures = IntStream.range(0, riskEnginesNum)
                .boxed()
                .collect(Collectors.toMap(
                        shardId -> shardId,
                        shardId -> CompletableFuture.supplyAsync(
                                () -> new RiskEngine(shardId, riskEnginesNum, serializationProcessor, sharedPool, exchangeConfiguration),
                                loaderExecutor)));

        // Wait for all matching engines to finish initializing (CompletableFuture.join blocks until ready)
        // Then wrap each engine in an EventHandler lambda that calls processOrder()
        // EventHandler interface: (event, sequence, endOfBatch) -> void
        final EventHandler<OrderCommand>[] matchingEngineHandlers = matchingEngineFutures.values().stream()
                .map(CompletableFuture::join)  // Block until engine is ready
                .map(mer -> (EventHandler<OrderCommand>) (cmd, seq, eob) -> mer.processOrder(seq, cmd))
                .toArray(ExchangeCore::newEventHandlersArray);

        // Wait for all risk engines to finish initializing
        // Unlike matching engines, we keep the RiskEngine objects (not just handlers)
        // because we need to reference them when building R1 and R2 processors
        final Map<Integer, RiskEngine> riskEngines = riskEngineFutures.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> entry.getValue().join()));  // Block until engine is ready


        // ============================================================================
        // STEP 4: BUILD THE EVENT PROCESSING PIPELINE
        // ============================================================================
        //
        // UNDERSTANDING DISRUPTOR PIPELINE SYNTAX:
        // ----------------------------------------
        // disruptor.handleEventsWith(handler)
        //   → Creates a NEW stage with handler(s), returns EventHandlerGroup
        //   → Handler(s) read events from RingBuffer starting at sequence 0
        //
        // group.handleEventsWith(handler)
        //   → Adds handler to run AFTER the group, IN PARALLEL with other handlers at same level
        //   → Creates a dependency: handler waits for group to finish each event
        //
        // disruptor.after(handlers).handleEventsWith(newHandler)
        //   → Creates newHandler that waits for ALL handlers to complete each event
        //   → Sequential dependency: newHandler runs after handlers finish
        //
        // EventHandlerGroup
        //   → Represents a collection of handlers at the same pipeline stage
        //   → Used to express dependencies: "these handlers must finish before next stage"
        //
        // PIPELINE VISUALIZATION:
        // ----------------------
        //                            [RingBuffer]
        //                                 ↓
        //                    ┌────────────────────────┐
        //                    │   Grouping (G)         │  ← Stage 1: Prepare commands
        //                    └────────────────────────┘
        //                                 ↓
        //            ┌────────────────────┼────────────────────┐
        //            ↓                    ↓                     ↓
        //    ┌──────────────┐   ┌─────────────────┐   ┌─────────────────┐
        //    │Journaling (J)│   │Risk Hold (R1_0) │   │Risk Hold (R1_1) │  ← Stage 2: Parallel
        //    └──────────────┘   └─────────────────┘   └─────────────────┘
        //            │                    ↓──────────────────────↓
        //            │           ┌────────────────────────────────────┐
        //            │           │  Matching Engines (ME0, ME1, ...)  │  ← Stage 3: Execute trades
        //            │           └────────────────────────────────────┘
        //            │                    ↓──────────────────────↓
        //            │           ┌─────────────────┐   ┌─────────────────┐
        //            │           │Risk Release(R2_0│   │Risk Release(R2_1│  ← Stage 4: Release funds
        //            │           └─────────────────┘   └─────────────────┘
        //            └────────────────────┼────────────────────┘
        //                                 ↓
        //                    ┌────────────────────────┐
        //                    │   Results Handler (E)  │  ← Stage 5: Send results
        //                    └────────────────────────┘
        //
        // Lists to track R1 and R2 processors (needed to link them later)
        final List<TwoStepMasterProcessor> procR1 = new ArrayList<>(riskEnginesNum);
        final List<TwoStepSlaveProcessor> procR2 = new ArrayList<>(riskEnginesNum);

        // ============================================================================
        // STAGE 1: GROUPING PROCESSOR (G)
        // ============================================================================
        // First handler in the pipeline - reads events from RingBuffer sequence 0, 1, 2, ...
        //
        // GroupingProcessor responsibilities:
        // - Groups related commands together (e.g., multi-leg orders)
        // - Pre-processes commands before risk/matching
        // - Can batch multiple commands for efficiency
        //
        // Returns: EventHandlerGroup representing "afterGrouping" stage
        // All subsequent handlers will depend on this completing first
        final EventHandlerGroup<OrderCommand> afterGrouping =
                disruptor.handleEventsWith((rb, bs) -> new GroupingProcessor(rb, rb.newBarrier(bs), perfCfg, coreWaitStrategy, sharedPool));

        // ============================================================================
        // STAGE 2: JOURNALING (J) + RISK HOLD (R1) - RUN IN PARALLEL
        // ============================================================================
        //
        // WHY PARALLEL?
        // - Journaling writes to disk (I/O bound)
        // - Risk processing reads/writes memory (CPU bound)
        // - They don't depend on each other, so can run simultaneously
        // - This overlaps I/O latency with CPU work, improving throughput

        // --- JOURNALING HANDLER (Optional) ---
        // Writes commands to disk for crash recovery
        // If journaling disabled, this handler is not added to pipeline
        boolean enableJournaling = serializationCfg.isEnableJournaling();
        final EventHandler<OrderCommand> jh = enableJournaling ? serializationProcessor::writeToJournal : null;

        if (enableJournaling) {
            // Add journaling handler AFTER grouping, IN PARALLEL with R1 handlers (added below)
            afterGrouping.handleEventsWith(jh);
        }

        // --- RISK HOLD HANDLERS (R1) ---
        // Create one R1 handler per RiskEngine shard
        // Each R1 handler:
        // 1. Checks if user has sufficient funds/positions
        // 2. Reserves (holds) the required funds/positions
        // 3. Lets the command proceed to matching engine
        //
        // TwoStepMasterProcessor: Special processor that will coordinate with TwoStepSlaveProcessor (R2)
        // - Master (R1) reserves resources BEFORE matching
        // - Slave (R2) commits or releases resources AFTER matching
        // - They communicate to ensure atomicity of the two-step process
        riskEngines.forEach((idx, riskEngine) -> afterGrouping.handleEventsWith(
                (rb, bs) -> {
                    final TwoStepMasterProcessor r1 = new TwoStepMasterProcessor(rb, rb.newBarrier(bs), riskEngine::preProcessCommand, exceptionHandler, coreWaitStrategy, "R1_" + idx);
                    procR1.add(r1);  // Save reference for linking to R2 later
                    return r1;
                }));

        // ============================================================================
        // STAGE 3: MATCHING ENGINES (ME)
        // ============================================================================
        // Matching engines run AFTER all R1 processors complete (funds are reserved)
        // They execute trades by matching orders in order books
        //
        // disruptor.after(procR1) creates a dependency barrier:
        // - All R1 processors must finish event N before ANY matching engine starts event N
        // - This ensures funds are reserved before we attempt to match orders
        //
        // Multiple matching engines run in PARALLEL:
        // - Each handles different symbols (sharded by symbol hash)
        // - No cross-shard dependencies, so they can run concurrently
        disruptor.after(procR1.toArray(new TwoStepMasterProcessor[0])).handleEventsWith(matchingEngineHandlers);

        // ============================================================================
        // STAGE 4: RISK RELEASE (R2)
        // ============================================================================
        // Risk release runs AFTER matching engines complete
        // Now we know the trade outcomes, so we can:
        // - COMMIT reserved funds if trade executed
        // - RELEASE reserved funds if trade failed or partially filled
        //
        // Get a group representing "after matching engines complete"
        final EventHandlerGroup<OrderCommand> afterMatchingEngine = disruptor.after(matchingEngineHandlers);

        // Create one R2 handler per RiskEngine (mirrors R1 structure)
        // TwoStepSlaveProcessor: Completes the two-step process started by R1
        // - Receives coordination signals from its paired R1 master
        // - Performs the final fund/position updates based on trade results
        riskEngines.forEach((idx, riskEngine) -> afterMatchingEngine.handleEventsWith(
                (rb, bs) -> {
                    final TwoStepSlaveProcessor r2 = new TwoStepSlaveProcessor(rb, rb.newBarrier(bs), riskEngine::handlerRiskRelease, exceptionHandler, "R2_" + idx);
                    procR2.add(r2);  // Save reference for linking to R1
                    return r2;
                }));


        // ============================================================================
        // STAGE 5: RESULTS HANDLER (E)
        // ============================================================================
        // Final stage - sends results back to API clients
        //
        // Must wait for BOTH:
        // 1. Matching engines to complete (trade execution done)
        // 2. Journaling to complete (IF enabled - command persisted to disk)
        //
        // Why wait for journaling?
        // - We can't send "order filled" to client until it's safely written to disk
        // - Otherwise, a crash could lose the order, but client thinks it executed
        //
        // If journaling enabled: wait for [ME handlers] + [J handler]
        // If journaling disabled: wait for [ME handlers] only
        final EventHandlerGroup<OrderCommand> mainHandlerGroup = enableJournaling
                ? disruptor.after(arraysAddHandler(matchingEngineHandlers, jh))
                : afterMatchingEngine;

        // ResultsHandler sends command results to the resultsConsumer callback
        final ResultsHandler resultsHandler = new ResultsHandler(resultsConsumer);

        // Add the final handler that:
        // 1. Invokes the user's resultsConsumer callback
        // 2. Updates the API's internal result tracking (for synchronous calls)
        mainHandlerGroup.handleEventsWith((cmd, seq, eob) -> {
            resultsHandler.onEvent(cmd, seq, eob);
            api.processResult(seq, cmd); // TODO SLOW ?(volatile operations)
        });

        // ============================================================================
        // STEP 5: LINK TWO-STEP PROCESSORS (R1 ↔ R2)
        // ============================================================================
        // Connect each R1 (master) to its corresponding R2 (slave)
        // This enables coordination between reserve and release operations:
        // - R1 can signal R2 about what funds were reserved
        // - R2 knows what to commit/release based on trade outcome
        // - Ensures atomicity: reserve + match + release happen as one logical unit
        IntStream.range(0, riskEnginesNum).forEach(i -> procR1.get(i).setSlaveProcessor(procR2.get(i)));

        // ============================================================================
        // STEP 6: CLEANUP
        // ============================================================================
        // Shut down the executor used for parallel engine initialization
        // All engines are now created and wired into the pipeline
        try {
            loaderExecutor.shutdown();
            loaderExecutor.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            throw new RuntimeException(ex);
        }

        // PIPELINE IS NOW FULLY CONSTRUCTED!
        // Call startup() to begin processing events
    }

    /**
     * Starts the exchange core.
     *
     * WHAT HAPPENS ON STARTUP:
     * 1. Disruptor.start() spawns all consumer threads (one per EventHandler)
     * 2. Each thread enters its event loop, waiting for events to process
     * 3. Replay journal from disk (if crash recovery enabled)
     * 4. Enable journaling for new commands
     *
     * After startup(), the exchange is ready to accept commands via ExchangeApi
     *
     * THREADING MODEL AFTER START:
     * - Producer threads: Multiple threads calling api.submitCommand() write to RingBuffer
     * - Consumer threads: One thread per EventHandler reads from RingBuffer
     * - Example with 2 ME, 2 RE:
     *   Thread 1: GroupingProcessor
     *   Thread 2: JournalingHandler (if enabled)
     *   Thread 3: RiskHold_0 (R1_0)
     *   Thread 4: RiskHold_1 (R1_1)
     *   Thread 5: MatchingEngine_0
     *   Thread 6: MatchingEngine_1
     *   Thread 7: RiskRelease_0 (R2_0)
     *   Thread 8: RiskRelease_1 (R2_1)
     *   Thread 9: ResultsHandler
     */
    public synchronized void startup() {
        if (!started) {
            log.debug("Starting disruptor...");
            // Spawn all consumer threads and start the event processing pipeline
            disruptor.start();
            started = true;

            // Crash recovery: replay all commands from journal, then enable journaling for new commands
            // This restores the exchange to its pre-crash state
            serializationProcessor.replayJournalFullAndThenEnableJouraling(exchangeConfiguration.getInitStateCfg(), api);
        }
    }

    /**
     * Provides ExchangeApi instance.
     *
     * The API is used to submit commands to the exchange:
     * - api.submitCommand() - synchronous (blocks until result ready)
     * - api.submitCommandAsync() - asynchronous (returns CompletableFuture)
     *
     * @return ExchangeApi instance (always same object)
     */
    public ExchangeApi getApi() {
        return api;
    }

    /**
     * Special event that signals all handlers to stop processing.
     * Published to RingBuffer during shutdown to ensure graceful termination.
     *
     * How it works:
     * 1. shutdown() publishes this SHUTDOWN_SIGNAL event
     * 2. All handlers process their remaining events
     * 3. When handlers see SHUTDOWN_SIGNAL, they stop their event loops
     * 4. All threads terminate cleanly
     */
    private static final EventTranslator<OrderCommand> SHUTDOWN_SIGNAL_TRANSLATOR = (cmd, seq) -> {
        cmd.command = OrderCommandType.SHUTDOWN_SIGNAL;
        cmd.resultCode = CommandResultCode.NEW;
    };

    /**
     * Shut down disruptor with infinite timeout (wait for all events to complete).
     */
    public synchronized void shutdown() {
        shutdown(-1, TimeUnit.MILLISECONDS);
    }

    /**
     * Gracefully shut down the exchange core.
     *
     * SHUTDOWN PROCESS:
     * 1. Publish SHUTDOWN_SIGNAL to RingBuffer
     * 2. Wait for all handlers to process remaining events
     * 3. All consumer threads terminate
     * 4. Exchange is fully stopped
     *
     * Will throw IllegalStateException if shutdown times out (events still processing).
     *
     * @param timeout  the amount of time to wait for all events to be processed. <code>-1</code> will give an infinite timeout
     * @param timeUnit the unit the timeOut is specified in
     */
    public synchronized void shutdown(final long timeout, final TimeUnit timeUnit) {
        if (!stopped) {
            stopped = true;
            // TODO stop accepting new events first
            try {
                log.info("Shutdown disruptor...");
                // Publish shutdown signal - all handlers will see this and terminate
                ringBuffer.publishEvent(SHUTDOWN_SIGNAL_TRANSLATOR);
                // Wait for all handlers to finish processing and threads to stop
                disruptor.shutdown(timeout, timeUnit);
                log.info("Disruptor stopped");
            } catch (TimeoutException e) {
                throw new IllegalStateException("could not stop a disruptor gracefully. Not all events may be executed.");
            }
        }
    }

    /**
     * Helper method to append a handler to an array of handlers.
     * Used to combine matching engine handlers with journaling handler.
     */
    private static EventHandler<OrderCommand>[] arraysAddHandler(EventHandler<OrderCommand>[] handlers, EventHandler<OrderCommand> extraHandler) {
        final EventHandler<OrderCommand>[] result = Arrays.copyOf(handlers, handlers.length + 1);
        result[handlers.length] = extraHandler;
        return result;
    }

    /**
     * Helper method to create a generic array of EventHandlers.
     * Java doesn't allow new EventHandler<OrderCommand>[size] due to type erasure.
     */
    @SuppressWarnings(value = {"unchecked"})
    private static EventHandler<OrderCommand>[] newEventHandlersArray(int size) {
        return new EventHandler[size];
    }
}
