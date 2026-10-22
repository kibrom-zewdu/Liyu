package com.liyulottery.telegram;

import com.liyulottery.common.CustomerLanguage;
import com.liyulottery.common.TranslationService;
import com.liyulottery.user.TelegramUserService;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Locale;

@Service
@ConditionalOnProperty(prefix = "app.telegram", name = "enabled", havingValue = "true")
public class TelegramConversationHandler {
    private final TelegramApiClient telegram;
    private final TelegramUserService users;
    private final TranslationService translations;

    public TelegramConversationHandler(TelegramApiClient telegram, TelegramUserService users, TranslationService translations) {
        this.telegram = telegram;
        this.users = users;
        this.translations = translations;
    }

    public void handle(Map<String, Object> update) {
        Map<?, ?> message = map(update.get("message"));
        if (message.isEmpty()) return;
        Map<?, ?> from = map(message.get("from"));
        Map<?, ?> chat = map(message.get("chat"));
        if (!(from.get("id") instanceof Number id) || !(chat.get("id") instanceof Number chatId)) return;

        String username = string(from.get("username"));
        String firstName = string(from.get("first_name"));
        String lastName = string(from.get("last_name"));
        String displayName = ((firstName + " " + lastName).trim());
        String languageCode = users.touch(id.longValue(), username, displayName.isBlank() ? null : displayName);
        String text = string(message.get("text")).trim();
        long targetChat = chatId.longValue();

        Optional<CustomerLanguage> chosen = CustomerLanguage.fromButtonText(text);
        if (chosen.isPresent()) {
            users.setLanguage(id.longValue(), chosen.get());
            String language = chosen.get().code();
            telegram.sendMessage(targetChat, translations.text("welcome.selected", language) + "\n\n" +
                    translations.text("campaign.none", language), mainMenu(language));
            return;
        }

        if (languageCode == null || languageCode.isBlank()) {
            if (text.startsWith("/start") || text.startsWith("/language") || text.startsWith("/help")) {
                telegram.sendMessage(targetChat, translations.text("welcome.choose_language", "en"), languageMenu());
            } else {
                telegram.sendMessage(targetChat, translations.text("language.required", "en"), languageMenu());
            }
            return;
        }

        String language = CustomerLanguage.fromCode(languageCode).map(CustomerLanguage::code).orElse("en");
        String command = text.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        if (command.startsWith("/start")) {
            telegram.sendMessage(targetChat, translations.text("welcome.returning", language) + "\n\n" +
                    translations.text("campaign.none", language), mainMenu(language));
        } else if (command.startsWith("/help") || text.equals(translations.text("menu.help", language))) {
            send(targetChat, "help.instructions", language, mainMenu(language));
        } else if (command.startsWith("/language") || text.equals(translations.text("menu.language", language))) {
            send(targetChat, "language.choose", language, languageMenu());
        } else if (command.startsWith("/prizes") || text.equals(translations.text("menu.prizes", language))) {
            send(targetChat, "campaign.none_prizes", language, mainMenu(language));
        } else if (command.startsWith("/tickets") || text.equals(translations.text("menu.tickets", language))) {
            send(targetChat, "tickets.empty", language, mainMenu(language));
        } else if (command.startsWith("/results") || text.equals(translations.text("menu.results", language))) {
            send(targetChat, "results.none", language, mainMenu(language));
        } else if (text.equals(translations.text("menu.buy", language))) {
            send(targetChat, "campaign.none_buy", language, mainMenu(language));
        } else if (text.equals(translations.text("menu.draw", language))) {
            send(targetChat, "campaign.none_draw", language, mainMenu(language));
        } else {
            send(targetChat, "help.prompt", language, mainMenu(language));
        }
    }

    private void send(long chatId, String key, String language, Map<String, Object> keyboard) {
        telegram.sendMessage(chatId, translations.text(key, language), keyboard);
    }

    private Map<String, Object> languageMenu() {
        return Map.of("keyboard", List.of(
                List.of(CustomerLanguage.ENGLISH.buttonText()),
                List.of(CustomerLanguage.AMHARIC.buttonText()),
                List.of(CustomerLanguage.TIGRIGNA.buttonText())),
                "resize_keyboard", true, "one_time_keyboard", true);
    }

    private Map<String, Object> mainMenu(String language) {
        List<List<String>> keyboard = new ArrayList<>();
        keyboard.add(List.of(translations.text("menu.prizes", language), translations.text("menu.buy", language)));
        keyboard.add(List.of(translations.text("menu.tickets", language), translations.text("menu.draw", language)));
        keyboard.add(List.of(translations.text("menu.results", language), translations.text("menu.help", language)));
        keyboard.add(List.of(translations.text("menu.language", language)));
        return Map.of("keyboard", keyboard, "resize_keyboard", true, "is_persistent", true);
    }

    private static String string(Object value) { return value == null ? "" : value.toString(); }
    private static Map<?, ?> map(Object value) { return value instanceof Map<?, ?> result ? result : Map.of(); }
}
