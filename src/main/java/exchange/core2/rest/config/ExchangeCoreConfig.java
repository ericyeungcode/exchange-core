package exchange.core2.rest.config;

import exchange.core2.core.ExchangeApi;
import exchange.core2.core.ExchangeCore;
import exchange.core2.core.IEventsHandler;
import exchange.core2.core.SimpleEventsProcessor;
import exchange.core2.core.common.*;
import exchange.core2.core.common.config.ExchangeConfiguration;
import exchange.core2.core.utils.AffinityThreadFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PreDestroy;

/**
 * Spring configuration for ExchangeCore.
 *
 * LIFECYCLE:
 * 1. Bean creation: Constructs ExchangeCore with default configuration
 * 2. Startup: Calls exchangeCore.startup() to initialize Disruptor threads
 * 3. Ready: ExchangeApi bean is available for controllers
 * 4. Shutdown: PreDestroy hook calls exchangeCore.shutdown() for graceful termination
 *
 * CONFIGURATION:
 * - Default configuration with optimized performance settings
 * - SimpleEventsProcessor logs all events to console
 * - Thread affinity enabled for better CPU cache utilization
 *
 * CUSTOMIZATION:
 * Override this configuration or use application.properties to customize:
 * - Ring buffer size
 * - Number of matching engines
 * - Number of risk engines
 * - Wait strategy
 * - Journaling settings
 */
@Slf4j
@Configuration
public class ExchangeCoreConfig {

    private ExchangeCore exchangeCore;

    /**
     * Creates and starts the ExchangeCore instance.
     *
     * STARTUP PROCESS:
     * 1. Create event processor to handle trade/reject/reduce events
     * 2. Build ExchangeConfiguration with default settings
     * 3. Construct ExchangeCore (wires up Disruptor pipeline)
     * 4. Start ExchangeCore (spawns consumer threads, begins processing)
     *
     * @return ExchangeCore instance (managed by Spring)
     */
    @Bean
    public ExchangeCore exchangeCore() {
        log.info("Initializing ExchangeCore...");

        // Event processor - logs all events to console
        // In production, replace with proper event handling (Kafka, database, etc.)
        SimpleEventsProcessor eventsProcessor = new SimpleEventsProcessor(new IEventsHandler() {
            @Override
            public void tradeEvent(TradeEvent tradeEvent) {
                // TradeEvent contains a list of trades, log summary
                String tradesInfo = tradeEvent.getTrades().stream()
                        .map(t -> String.format("maker=%d@%d×%d", t.getMakerUid(), t.getPrice(), t.getVolume()))
                        .reduce((a, b) -> a + ", " + b)
                        .orElse("no trades");

                log.info("Trade: symbol={}, totalVolume={}, taker={}, trades=[{}]",
                        tradeEvent.getSymbol(),
                        tradeEvent.getTotalVolume(),
                        tradeEvent.getTakerUid(),
                        tradesInfo);
            }

            @Override
            public void reduceEvent(ReduceEvent reduceEvent) {
                log.info("Reduce: uid={}, orderId={}, symbol={}, reducedVolume={}",
                        reduceEvent.getUid(),
                        reduceEvent.getOrderId(),
                        reduceEvent.getSymbol(),
                        reduceEvent.getReducedVolume());
            }

            @Override
            public void rejectEvent(RejectEvent rejectEvent) {
                log.warn("Reject: uid={}, orderId={}, symbol={}, price={}, rejectedVolume={}",
                        rejectEvent.getUid(),
                        rejectEvent.getOrderId(),
                        rejectEvent.getSymbol(),
                        rejectEvent.getPrice(),
                        rejectEvent.getRejectedVolume());
            }

            @Override
            public void commandResult(ApiCommandResult commandResult) {
                log.debug("Command result: {}", commandResult);
            }

            @Override
            public void orderBook(OrderBook orderBook) {
                log.debug("OrderBook update: symbol={}", orderBook.getSymbol());
            }
        });

        // Build default configuration
        // In production, customize via ExchangeConfiguration builder
        ExchangeConfiguration conf = ExchangeConfiguration.defaultBuilder().build();

        // Create ExchangeCore
        this.exchangeCore = ExchangeCore.builder()
                .resultsConsumer(eventsProcessor)
                .exchangeConfiguration(conf)
                .build();

        // Start Disruptor threads
        log.info("Starting ExchangeCore...");
        this.exchangeCore.startup();
        log.info("ExchangeCore started successfully");

        return this.exchangeCore;
    }

    /**
     * Exposes ExchangeApi as a Spring bean for controllers to use.
     *
     * The API provides methods to:
     * - Submit commands synchronously (blocking until result ready)
     * - Submit commands asynchronously (returns CompletableFuture)
     * - Query market data and reports
     *
     * @param exchangeCore the ExchangeCore instance
     * @return ExchangeApi for submitting commands
     */
    @Bean
    public ExchangeApi exchangeApi(ExchangeCore exchangeCore) {
        return exchangeCore.getApi();
    }

    /**
     * Gracefully shutdown ExchangeCore when Spring context closes.
     *
     * SHUTDOWN PROCESS:
     * 1. Stop accepting new commands (future enhancement)
     * 2. Publish SHUTDOWN_SIGNAL to Disruptor
     * 3. Wait for all handlers to finish processing remaining events
     * 4. Terminate all consumer threads
     * 5. Release resources
     */
    @PreDestroy
    public void shutdown() {
        if (exchangeCore != null) {
            log.info("Shutting down ExchangeCore...");
            exchangeCore.shutdown();
            log.info("ExchangeCore shutdown complete");
        }
    }
}
