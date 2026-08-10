package com.grassland.marketplace.workflow.saga;

import com.grassland.marketplace.taskcatalog.AcceptanceCommand;
import com.grassland.marketplace.taskcatalog.AcceptanceCommandRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Recovers acceptance workflows after a DB commit / Temporal start interruption. */
@Component
@ConditionalOnProperty(prefix = "marketplace.acceptance", name = "dispatcher-enabled",
        havingValue = "true", matchIfMissing = true)
public class AcceptanceWorkflowDispatcher {

    private static final Logger log = LoggerFactory.getLogger(AcceptanceWorkflowDispatcher.class);

    private final AcceptanceCommandRepository commands;
    private final AcceptanceWorkflowStarter starter;
    private final int batchSize;

    public AcceptanceWorkflowDispatcher(
            AcceptanceCommandRepository commands,
            AcceptanceWorkflowStarter starter,
            @org.springframework.beans.factory.annotation.Value(
                    "${marketplace.acceptance.dispatcher-batch-size:32}") int batchSize) {
        this.commands = commands;
        this.starter = starter;
        this.batchSize = Math.max(1, batchSize);
    }

    @Scheduled(fixedDelayString = "${marketplace.acceptance.dispatcher-poll-ms:2000}")
    public void dispatch() {
        Mono.fromRunnable(this::dispatchBatch).subscribeOn(Schedulers.boundedElastic()).subscribe();
    }

    void dispatchBatch() {
        List<AcceptanceCommand> rows = commands.findDispatchable(batchSize).collectList().block();
        if (rows == null) {
            return;
        }
        for (AcceptanceCommand command : rows) {
            try {
                starter.start(command).block();
                commands.markStarted(command.id()).block();
            } catch (RuntimeException failure) {
                log.warn("acceptance workflow dispatch failed command={} application={}",
                        command.id(), command.applicationId(), failure);
            }
        }
    }
}
