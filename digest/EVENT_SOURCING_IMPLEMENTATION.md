# Event Sourcing Implementation in Exchange-Core

## Overview

Exchange-core implements a robust event sourcing pattern to ensure durability, auditability, and recoverability of all state changes in the exchange system. The implementation provides:

- **Disk journaling** - All state-mutating commands are written to disk in sequential journal files
- **State snapshots** - Periodic serialization of complete system state to disk
- **Journal replay** - Ability to reconstruct system state by replaying commands from journal
- **LZ4 compression** - Efficient storage with optional compression for both journals and snapshots
- **Deterministic execution** - Commands are processed in a strict order ensuring reproducible state

## Core Concepts

### Commands as Events

In exchange-core, **commands are the events** in the event sourcing pattern. Every operation that can mutate state is represented as an `OrderCommand` object that flows through the system:

```
OrderCommand fields:
- command: OrderCommandType (PLACE_ORDER, CANCEL_ORDER, MOVE_ORDER, etc.)
- orderId, uid, symbol, price, size, etc.
- timestamp: when the command was created
- resultCode: outcome of command execution
- matcherEvent: chain of trade events generated
- eventsGroup: grouping ID for related commands
```

Commands flow through a LMAX Disruptor ring buffer in a strict sequential order, ensuring deterministic processing.

### Command Types

Commands are categorized by their behavior:

**Mutating Commands** (journaled):
- `PLACE_ORDER` - Place a new order
- `CANCEL_ORDER` - Cancel an existing order
- `MOVE_ORDER` - Move order to new price
- `REDUCE_ORDER` - Reduce order size
- `BALANCE_ADJUSTMENT` - Adjust user balance
- `ADD_USER` - Create new user
- `SUSPEND_USER` / `RESUME_USER` - User account management
- `BINARY_DATA_COMMAND` - Batch operations (symbols, accounts)

**Query Commands** (not journaled):
- `ORDER_BOOK_REQUEST` - Query order book state
- `BINARY_DATA_QUERY` - Query reports

**Control Commands**:
- `PERSIST_STATE_MATCHING` - Trigger matching engine snapshot
- `PERSIST_STATE_RISK` - Trigger risk engine snapshot
- `RESET` - Clear all state
- `SHUTDOWN_SIGNAL` - Graceful shutdown

## Architecture Components

### 1. ISerializationProcessor Interface

The central abstraction for event sourcing operations:

```java
public interface ISerializationProcessor {
    // Snapshot operations
    boolean storeData(long snapshotId, long seq, long timestampNs,
                      SerializedModuleType type, int instanceId,
                      WriteBytesMarshallable obj);

    <T> T loadData(long snapshotId, SerializedModuleType type,
                   int instanceId, Function<BytesIn, T> initFunc);

    // Journal operations
    void writeToJournal(OrderCommand cmd, long dSeq, boolean eob);
    void enableJournaling(long afterSeq, ExchangeApi api);

    // Replay operations
    long replayJournalFull(InitialStateConfiguration cfg, ExchangeApi api);
    void replayJournalFullAndThenEnableJouraling(InitialStateConfiguration cfg, ExchangeApi api);
}
```

**Implementations:**
- `DiskSerializationProcessor` - Full-featured implementation with disk persistence
- `DummySerializationProcessor` - No-op implementation for in-memory only mode

### 2. DiskSerializationProcessor

The production implementation of event sourcing with disk persistence.

#### Journal Writing

Journal files store commands in a compact binary format:

```
For each command:
- 1 byte: command type code
- 8 bytes: sequence number
- 8 bytes: timestamp
- 4 bytes: service flags
- 8 bytes: events group ID
- Variable: command-specific fields (uid, symbol, orderId, price, size, etc.)
```

**Key features:**
- **Buffered writes** - Commands are buffered in memory (default 32KB) before flushing
- **Compression** - Batches exceeding threshold are LZ4-compressed
- **File rotation** - New files created when size limit reached or snapshot taken
- **Synchronous flush** - End-of-batch (EOB) flag triggers immediate disk sync

**File naming convention:**
```
{exchangeId}_journal_{snapshotId}_{partitionId}.ecj
Example: MY_EXCHANGE_journal_0_0001.ecj
```

#### Snapshot Mechanism

Snapshots capture complete system state at a point in time. Two module types are serialized:

1. **Risk Engine (RE)** - User profiles, positions, balances, symbol specifications
2. **Matching Engine Router (ME)** - Order books state

**Snapshot process:**
1. `PERSIST_STATE_MATCHING` command triggers matching engine serialization
2. `PERSIST_STATE_RISK` command triggers risk engine serialization and journal rotation
3. Each shard (for horizontal scaling) writes its own snapshot file
4. State is serialized using Chronicle Wire (binary format) with LZ4 compression

**File naming convention:**
```
{exchangeId}_snapshot_{snapshotId}_{moduleType}{instanceId}.ecs
Example: MY_EXCHANGE_snapshot_12345_RE0.ecs  (Risk Engine instance 0)
         MY_EXCHANGE_snapshot_12345_ME0.ecs  (Matching Engine instance 0)
```

**State serialization:**

Risk Engine serializes:
- `SymbolSpecificationProvider` - All symbol configurations
- `UserProfileService` - All user accounts, balances, positions
- `BinaryCommandsProcessor` - Batch command state
- `lastPriceCache` - Latest bid/ask prices per symbol
- `fees`, `adjustments`, `suspends` - Accounting aggregates

Matching Engine serializes:
- `BinaryCommandsProcessor` - Batch command state
- `orderBooks` - Complete order book state for all symbols

### 3. Journal and Snapshot Descriptors

Metadata tracking for recovery:

**SnapshotDescriptor:**
```java
- snapshotId: Unique identifier
- seq: Command sequence when snapshot was taken
- timestampNs: Timestamp of snapshot
- prev/next: Linked list of snapshots
- journals: Map of journal files based on this snapshot
```

**JournalDescriptor:**
```java
- timestampNs: When journal started
- seqFirst/seqLast: Sequence range covered
- baseSnapshot: Snapshot this journal is based on
- prev/next: Linked list of journal files
```

### 4. Disruptor Pipeline Integration

Event sourcing is integrated into the LMAX Disruptor processing pipeline:

```
[Grouping] → [Journaling] → [Risk Hold] → [Matching] → [Risk Release] → [Results]
              ↓
         Journal files

[PERSIST_STATE commands] → [Snapshot files]
```

**Pipeline stages:**
1. **Grouping Processor (G)** - Groups related commands for batch processing
2. **Journaling Handler (J)** - Writes commands to journal (parallel with R1)
3. **Risk Hold (R1)** - Pre-processes commands in risk engine
4. **Matching Engine (ME)** - Processes orders in order books
5. **Risk Release (R2)** - Post-processes trade results
6. **Results Handler (E)** - Publishes results to consumers

The journaling handler runs in parallel with risk/matching processing, ensuring minimal impact on latency.

## Journal Replay and Recovery

### Startup Recovery Process

On startup, `ExchangeCore` executes recovery:

```java
// In ExchangeCore.startup()
serializationProcessor.replayJournalFullAndThenEnableJouraling(
    exchangeConfiguration.getInitStateCfg(), api);
```

**Recovery sequence:**

1. **Load Snapshots** (if configured)
   - Check if snapshot files exist for configured snapshot ID
   - Load Risk Engine state for each shard
   - Load Matching Engine state for each shard
   - Reconstruct all in-memory data structures

2. **Replay Journal** (if configured)
   - Read journal partition files sequentially
   - Parse commands from binary format
   - Decompress LZ4 blocks
   - Submit commands through `ExchangeApi` to rebuild state
   - Stop at configured timestamp or end of journal

3. **Enable Journaling** (if configured)
   - Set sequence number after which to start journaling
   - Enable grouping control for batch processing
   - Start writing new commands to journal

### Configuration Options

**InitialStateConfiguration** provides three startup modes:

1. **Clean Start** - Start with empty state, no recovery
```java
InitialStateConfiguration.cleanStart("EXCHANGE_ID")
```

2. **From Snapshot Only** - Load from snapshot, no journal replay
```java
InitialStateConfiguration.fromSnapshotOnly(exchangeId, snapshotId, baseSeq)
```

3. **Full Recovery** - Load snapshot + replay journal
```java
InitialStateConfiguration.lastKnownStateFromJournal(exchangeId, snapshotId, baseSeq)
```

## File Organization

All persistence files are stored in a configurable folder (default: current directory).

```
{folder}/
├── {exchangeId}.eca                           # Main activity log
├── {exchangeId}_snapshot_{id}_RE0.ecs        # Risk Engine shard 0 snapshot
├── {exchangeId}_snapshot_{id}_RE1.ecs        # Risk Engine shard 1 snapshot
├── {exchangeId}_snapshot_{id}_ME0.ecs        # Matching Engine shard 0 snapshot
├── {exchangeId}_snapshot_{id}_ME1.ecs        # Matching Engine shard 1 snapshot
├── {exchangeId}_journal_{id}_0001.ecj        # Journal partition 1
├── {exchangeId}_journal_{id}_0002.ecj        # Journal partition 2
└── ...
```

## Performance Optimizations

### 1. Buffered Journal Writes

- Commands accumulate in a direct ByteBuffer (32KB default)
- Flush triggered when buffer fills or end-of-batch
- Reduces disk I/O operations

### 2. Batch Compression

- Batches exceeding threshold (4KB default) are LZ4-compressed
- Small batches written uncompressed to avoid overhead
- Compression marker allows mixed compressed/uncompressed in same file

### 3. Parallel Processing

- Journaling runs in parallel with matching engine
- Multiple shards can serialize state concurrently
- File I/O doesn't block command processing

### 4. Command Encoding Efficiency

Commands use compact binary encoding:
- Enumeration values packed into single bytes
- Order action + order type combined into single byte
- Field sizes minimized (only necessary precision)

### 5. Memory-Mapped Files

Chronicle Wire library uses memory-mapped files for efficient I/O operations.

## Determinism and Consistency

### Guaranteed Ordering

- Commands flow through single Disruptor ring buffer
- Sequence numbers ensure total ordering
- Sharded processors handle disjoint sets of symbols/users

### Atomic State Transitions

- Risk Engine pre-validates commands atomically
- Matching Engine executes trades atomically
- Risk Engine releases holds atomically
- All three phases use same sequence number

### Snapshot Consistency

- Snapshot commands flow through same pipeline
- All shards snapshot at same sequence point
- Journal rotates after snapshot completes
- Recovery replays from last consistent snapshot

## Usage Example

### Configuration with Journaling

```java
ExchangeConfiguration conf = ExchangeConfiguration.defaultBuilder()
    .initStateCfg(InitialStateConfiguration.cleanStartJournaling("MY_EXCHANGE"))
    .serializationCfg(SerializationConfiguration.builder()
        .enableJournaling(true)
        .serializationProcessorFactory(cfg ->
            new DiskSerializationProcessor(cfg,
                DiskSerializationProcessorConfiguration.createDefault()))
        .build())
    .build();

ExchangeCore core = ExchangeCore.builder()
    .resultsConsumer(eventsProcessor)
    .exchangeConfiguration(conf)
    .build();

core.startup(); // Performs recovery if configured
```

### Taking a Snapshot

```java
// Trigger snapshot creation
long snapshotId = System.currentTimeMillis();
api.submitCommandAsync(ApiPersistState.builder()
    .snapshotId(snapshotId)
    .build());
```

### Recovery from Snapshot + Journal

```java
ExchangeConfiguration conf = ExchangeConfiguration.defaultBuilder()
    .initStateCfg(InitialStateConfiguration.lastKnownStateFromJournal(
        "MY_EXCHANGE",
        lastSnapshotId,
        lastSequence))
    .serializationCfg(/* same as above */)
    .build();

// On startup, will load snapshot and replay journal
core.startup();
```

## Advantages of This Implementation

1. **Complete Audit Trail** - Every state change is recorded
2. **Point-in-Time Recovery** - Replay to any moment in history
3. **Crash Recovery** - Automatic recovery from last snapshot + journal
4. **Debugging** - Reproduce production issues by replaying journal
5. **Testing** - Verify correctness by comparing replayed state
6. **Compliance** - Full transaction history for regulatory requirements
7. **Horizontal Scaling** - Sharded architecture allows parallel recovery
8. **Low Latency Impact** - Journaling runs in parallel with matching
9. **Compression** - LZ4 provides good compression with minimal CPU cost

## Limitations and Considerations

1. **Disk I/O Dependency** - System throughput limited by disk write speed
2. **Storage Requirements** - Journal files grow continuously
3. **Recovery Time** - Long journals take time to replay
4. **Single Threaded Journal** - One journal writer per exchange instance
5. **No Journal Compaction** - Old journal files must be manually archived
6. **Snapshot Atomicity** - No cross-shard transaction support

## Related Files

**Core interfaces:**
- `ISerializationProcessor.java` - Main serialization interface
- `OrderCommand.java` - Command structure

**Implementations:**
- `DiskSerializationProcessor.java` - Disk persistence implementation
- `DummySerializationProcessor.java` - No-op implementation

**Descriptors:**
- `JournalDescriptor.java` - Journal metadata
- `SnapshotDescriptor.java` - Snapshot metadata

**State modules:**
- `RiskEngine.java` - Risk engine state serialization
- `MatchingEngineRouter.java` - Matching engine state serialization

**Configuration:**
- `InitialStateConfiguration.java` - Startup configuration
- `SerializationConfiguration.java` - Serialization configuration
- `DiskSerializationProcessorConfiguration.java` - Disk persistence configuration

**Pipeline:**
- `ExchangeCore.java` - Main orchestration and pipeline setup

## Summary

Exchange-core's event sourcing implementation provides a production-ready, high-performance solution for maintaining durability and auditability in a low-latency trading system. The combination of sequential journaling, periodic snapshots, and deterministic replay ensures that the exchange can recover from failures while maintaining microsecond-level latency for order processing. The design leverages modern Java performance techniques (LMAX Disruptor, direct ByteBuffers, memory-mapped files) and efficient compression (LZ4) to minimize the performance impact of persistence operations.
