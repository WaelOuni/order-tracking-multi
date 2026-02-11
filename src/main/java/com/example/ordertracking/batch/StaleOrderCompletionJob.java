package com.example.ordertracking.batch;

import com.example.ordertracking.application.service.OrderTrackingService;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class StaleOrderCompletionJob {

    private static final Logger log = LoggerFactory.getLogger(StaleOrderCompletionJob.class);

    private final OrderTrackingService service;
    private final MeterRegistry meterRegistry;

    public StaleOrderCompletionJob(OrderTrackingService service, MeterRegistry meterRegistry) {
        this.service = service;
        this.meterRegistry = meterRegistry;
    }

    @Scheduled(cron = "${app.jobs.stale-order-completion-cron:0 0/30 * * * *}")
    public void execute() {
        int processed = service.autoCompleteDeliveredForStaleShippedOrders();
        meterRegistry.counter("order.batch.stale.completed", "result", "success").increment(processed);
        log.info("Stale order completion job processed {} orders", processed);
    }
}
