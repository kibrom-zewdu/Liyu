package com.liyulottery.telegram;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.telegram")
public record TelegramProperties(boolean enabled, String token, String username,
                                 String initialSuperAdminId, String webhookSecret,
                                 int pollingTimeoutSeconds) {
}
