package com.liyulottery.telegram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Component
@ConditionalOnProperty(prefix = "app.telegram", name = "enabled", havingValue = "true")
public class TelegramPollingWorker {
    private static final Logger log = LoggerFactory.getLogger(TelegramPollingWorker.class);
    private final TelegramApiClient api;
    private final TelegramUpdateQueue queue;
    private final TelegramConversationHandler handler;
    private final TelegramProperties properties;
    private final AtomicLong nextOffset = new AtomicLong(0);

    public TelegramPollingWorker(TelegramApiClient api, TelegramUpdateQueue queue,
                                 TelegramConversationHandler handler, TelegramProperties properties) {
        this.api = api;
        this.queue = queue;
        this.handler = handler;
        this.properties = properties;
    }

    @Scheduled(fixedDelay = 1000)
    public void poll() {
        try {
            List<Map<String, Object>> updates = api.getUpdates(nextOffset.get(), properties.pollingTimeoutSeconds());
            for (Map<String, Object> update : updates) {
                if (update.get("update_id") instanceof Number id) {
                    queue.saveReceived(id.longValue(), update);
                    nextOffset.accumulateAndGet(id.longValue() + 1, Math::max);
                }
            }
            for (TelegramUpdateQueue.PendingUpdate pending : queue.pending(100)) {
                try {
                    handler.handle(pending.payload());
                    queue.markProcessed(pending.updateId());
                } catch (RuntimeException failure) {
                    queue.markFailed(pending.updateId());
                    log.warn("Telegram update processing failed; update will be retried ({})",
                            failure.getClass().getSimpleName());
                }
            }
        } catch (RuntimeException failure) {
            log.warn("Telegram polling failed; it will retry ({})", failure.getClass().getSimpleName());
        }
    }
}
