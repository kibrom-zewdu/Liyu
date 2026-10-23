package com.liyulottery.telegram;

import com.liyulottery.common.CustomerLanguage;
import com.liyulottery.common.TranslationService;
import com.liyulottery.campaign.CampaignService;
import com.liyulottery.campaign.CampaignView;
import com.liyulottery.campaign.PrizeView;
import com.liyulottery.admin.AdminConversationHandler;
import com.liyulottery.user.TelegramUserService;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Locale;
import java.time.ZoneId;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import org.springframework.beans.factory.annotation.Value;

@Service
@ConditionalOnProperty(prefix = "app.telegram", name = "enabled", havingValue = "true")
public class TelegramConversationHandler {
    private final TelegramApiClient telegram;
    private final TelegramUserService users;
    private final TranslationService translations;
    private final CampaignService campaigns;
    private final AdminConversationHandler adminConversations;
    private final ZoneId businessZone;

    public TelegramConversationHandler(TelegramApiClient telegram, TelegramUserService users, TranslationService translations,
                                       CampaignService campaigns, AdminConversationHandler adminConversations,
                                       @Value("${app.timezone:Africa/Addis_Ababa}") String timeZone) {
        this.telegram = telegram;
        this.users = users;
        this.translations = translations;
        this.campaigns = campaigns;
        this.adminConversations = adminConversations;
        this.businessZone = ZoneId.of(timeZone);
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

        String preferredLanguage = CustomerLanguage.fromCode(languageCode).map(CustomerLanguage::code).orElse("en");
        Optional<AdminConversationHandler.AdminReply> adminReply = adminConversations.handle(id.longValue(), text, preferredLanguage);
        if (adminReply.isPresent()) {
            telegram.sendMessage(targetChat, adminReply.get().text(), adminReply.get().replyMarkup());
            return;
        }

        Optional<CustomerLanguage> chosen = CustomerLanguage.fromButtonText(text);
        if (chosen.isPresent()) {
            users.setLanguage(id.longValue(), chosen.get());
            String language = chosen.get().code();
            telegram.sendMessage(targetChat, translations.text("welcome.selected", language) + "\n\n" +
                    campaignAnnouncement(language), mainMenu(language));
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
                    campaignAnnouncement(language), mainMenu(language));
        } else if (command.startsWith("/help") || text.equals(translations.text("menu.help", language))) {
            send(targetChat, "help.instructions", language, mainMenu(language));
        } else if (command.startsWith("/language") || text.equals(translations.text("menu.language", language))) {
            send(targetChat, "language.choose", language, languageMenu());
        } else if (command.startsWith("/prizes") || text.equals(translations.text("menu.prizes", language))) {
            sendPrizes(targetChat, language);
        } else if (command.startsWith("/tickets") || text.equals(translations.text("menu.tickets", language))) {
            send(targetChat, "tickets.empty", language, mainMenu(language));
        } else if (command.startsWith("/results") || text.equals(translations.text("menu.results", language))) {
            send(targetChat, "results.none", language, mainMenu(language));
        } else if (text.equals(translations.text("menu.buy", language))) {
            send(targetChat, "campaign.none_buy", language, mainMenu(language));
        } else if (text.equals(translations.text("menu.draw", language))) {
            sendDrawInformation(targetChat, language);
        } else {
            send(targetChat, "help.prompt", language, mainMenu(language));
        }
    }

    private void send(long chatId, String key, String language, Map<String, Object> keyboard) {
        telegram.sendMessage(chatId, translations.text(key, language), keyboard);
    }

    private String campaignAnnouncement(String language) {
        return campaigns.activeCampaign(language)
                .map(campaign -> translations.text("campaign.announcement", language, campaign.title(), campaign.description(),
                        campaign.ticketPrice().toPlainString(), campaign.currency()))
                .orElseGet(() -> translations.text("campaign.none", language));
    }

    private void sendPrizes(long chatId, String language) {
        Optional<CampaignView> active = campaigns.activeCampaign(language);
        if (active.isEmpty()) {
            send(chatId, "campaign.none_prizes", language, mainMenu(language));
            return;
        }
        List<PrizeView> prizeList = campaigns.prizes(active.get().id(), language);
        if (prizeList.isEmpty()) {
            send(chatId, "campaign.none_prizes", language, mainMenu(language));
            return;
        }
        StringBuilder response = new StringBuilder(translations.text("campaign.prize_header", language, active.get().title()));
        for (PrizeView prize : prizeList) {
            response.append("\n\n").append(translations.text("campaign.prize_item", language,
                    prize.name(), prize.quantity(), safe(prize.category())));
            if (prize.description() != null && !prize.description().isBlank()) response.append("\n").append(prize.description());
        }
        telegram.sendMessage(chatId, response.toString(), mainMenu(language));
    }

    private void sendDrawInformation(long chatId, String language) {
        Optional<CampaignView> active = campaigns.activeCampaign(language);
        if (active.isEmpty()) {
            send(chatId, "campaign.none_draw", language, mainMenu(language));
            return;
        }
        CampaignView campaign = active.get();
        telegram.sendMessage(chatId, translations.text("campaign.draw_info", language, campaign.title(),
                formatDate(campaign.salesCloseAt(), language), formatDate(campaign.drawAt(), language)), mainMenu(language));
    }

    private String formatDate(Instant instant, String language) {
        if (instant == null) return translations.text("campaign.date_pending", language);
        return DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm z", Locale.forLanguageTag(language))
                .withZone(businessZone).format(instant);
    }

    private static String safe(String value) { return value == null || value.isBlank() ? "—" : value; }

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
