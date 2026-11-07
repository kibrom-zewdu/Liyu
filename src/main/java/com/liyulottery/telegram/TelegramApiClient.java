package com.liyulottery.telegram;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.HashMap;

@Component
@ConditionalOnProperty(prefix = "app.telegram", name = "enabled", havingValue = "true")
public class TelegramApiClient {
    private final RestClient restClient;

    @Autowired
    public TelegramApiClient(TelegramProperties properties) {
        this(properties, RestClient.builder());
    }

    TelegramApiClient(TelegramProperties properties, RestClient.Builder builder) {
        if (properties.token() == null || properties.token().isBlank()) {
            throw new IllegalStateException("TELEGRAM_BOT_TOKEN must be configured when Telegram is enabled");
        }
        this.restClient = builder
                .baseUrl("https://api.telegram.org/bot" + properties.token() + "/")
                .build();
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getUpdates(long offset, int timeoutSeconds) {
        Map<String, Object> result = restClient.post().uri("getUpdates")
                .body(Map.of("offset", offset, "timeout", timeoutSeconds, "allowed_updates", List.of("message")))
                .retrieve().body(Map.class);
        if (result == null || !Boolean.TRUE.equals(result.get("ok"))) {
            throw new IllegalStateException("Telegram getUpdates returned an unsuccessful response");
        }
        return (List<Map<String, Object>>) result.getOrDefault("result", List.of());
    }

    public void sendMessage(long chatId, String text, Map<String, Object> replyMarkup) {
        Map<String, Object> request = Map.of("chat_id", chatId, "text", text, "reply_markup", replyMarkup);
        Map<?, ?> result = restClient.post().uri("sendMessage").body(request).retrieve().body(Map.class);
        if (result == null || !Boolean.TRUE.equals(result.get("ok"))) {
            throw new IllegalStateException("Telegram sendMessage returned an unsuccessful response");
        }
    }

    public void sendPhoto(long chatId, String fileId, String caption) {
        sendPhoto(chatId, fileId, caption, Map.of());
    }

    public void sendPhoto(long chatId, String fileId, String caption, Map<String, Object> replyMarkup) {
        Map<String, Object> request = new HashMap<>();
        request.put("chat_id", chatId);
        request.put("photo", fileId);
        request.put("caption", caption);
        if (replyMarkup != null && !replyMarkup.isEmpty()) request.put("reply_markup", replyMarkup);
        Map<String, Object> result = restClient.post().uri("sendPhoto")
                .body(request)
                .retrieve().body(Map.class);
        if (result == null || !Boolean.TRUE.equals(result.get("ok"))) {
            throw new IllegalStateException("Telegram sendPhoto returned an unsuccessful response");
        }
    }
}
