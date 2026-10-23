package com.liyulottery.telegram;

import com.liyulottery.common.CustomerLanguage;
import com.liyulottery.common.TranslationService;
import com.liyulottery.campaign.CampaignService;
import com.liyulottery.campaign.CampaignView;
import com.liyulottery.campaign.PrizeView;
import com.liyulottery.admin.AdminConversationHandler;
import com.liyulottery.user.TelegramUserService;
import com.liyulottery.order.OrderService;
import com.liyulottery.payment.PaymentReviewHandler;
import com.liyulottery.ticket.TicketIssuanceService;
import com.liyulottery.draw.DrawResultService;
import com.liyulottery.draw.DrawResultAdminHandler;
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
    private final OrderService orders;
    private final PaymentReviewHandler paymentReview;
    private final TicketIssuanceService ticketIssuance;
    private final DrawResultService drawResults;
    private final DrawResultAdminHandler drawResultAdmin;
    private final ZoneId businessZone;

    public TelegramConversationHandler(TelegramApiClient telegram, TelegramUserService users, TranslationService translations,
                                       CampaignService campaigns, AdminConversationHandler adminConversations,
                                       OrderService orders,
                                       PaymentReviewHandler paymentReview,
                                       TicketIssuanceService ticketIssuance,
                                       DrawResultService drawResults,
                                       DrawResultAdminHandler drawResultAdmin,
                                       @Value("${app.timezone:Africa/Addis_Ababa}") String timeZone) {
        this.telegram = telegram;
        this.users = users;
        this.translations = translations;
        this.campaigns = campaigns;
        this.adminConversations = adminConversations;
        this.orders = orders;
        this.paymentReview = paymentReview;
        this.ticketIssuance = ticketIssuance;
        this.drawResults = drawResults;
        this.drawResultAdmin = drawResultAdmin;
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
        Optional<String> resultsAdminReply=drawResultAdmin.handle(id.longValue(),text);
        if(resultsAdminReply.isPresent()) {
            telegram.sendMessage(targetChat,resultsAdminReply.get(),mainMenu(preferredLanguage));
            return;
        }
        Optional<String> paymentReply = paymentReview.handle(id.longValue(), targetChat, text);
        if(paymentReply.isPresent()) {
            telegram.sendMessage(targetChat,paymentReply.get(),mainMenu(preferredLanguage));
            return;
        }

        if (message.containsKey("photo") || message.containsKey("document")) {
            handleReceipt(message, id.longValue(), targetChat, preferredLanguage, text);
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
            sendTickets(targetChat,id.longValue(),text,language);
        } else if (command.startsWith("/results") || text.equals(translations.text("menu.results", language))) {
            sendResults(targetChat,language);
        } else if (text.equals(translations.text("menu.buy", language)) || command.startsWith("/buy")) {
            Optional<CampaignView> available = campaigns.openCampaign(language);
            if (available.isEmpty()) send(targetChat, "campaign.none_buy", language, mainMenu(language));
            else telegram.sendMessage(targetChat, translations.text("order.buy_campaign", language, available.get().id(), available.get().title(), available.get().ticketPrice(), available.get().currency()), mainMenu(language));
        } else if (command.startsWith("/order")) {
            quoteOrder(text, id.longValue(), targetChat, language);
        } else if (command.equals("/confirm")) {
            confirmOrder(id.longValue(), targetChat, language);
        } else if (text.equals(translations.text("menu.draw", language))) {
            sendDrawInformation(targetChat, language);
        } else {
            send(targetChat, "help.prompt", language, mainMenu(language));
        }
    }

    private void sendResults(long chatId,String language) {
        List<DrawResultService.PublishedResult> published=drawResults.published(language);
        if(published.isEmpty()) { send(chatId,"results.none",language,mainMenu(language)); return; }
        StringBuilder response=new StringBuilder();
        long prior=-1;
        for(var result:published) {
            if(result.resultId()!=prior) {
                if(!response.isEmpty()) response.append("\n\n");
                response.append(translations.text("results.header",language,result.campaignTitle(),result.version(),formatDate(result.publishedAt(),language)))
                        .append("\n").append(result.notice());
                prior=result.resultId();
            }
            response.append("\n").append(translations.text("results.entry",language,result.ticketNumber(),result.prizeName()==null?translations.text("results.prize_pending",language):result.prizeName()));
        }
        telegram.sendMessage(chatId,response.toString(),mainMenu(language));
    }

    private void sendTickets(long chatId,long telegramUserId,String command,String language) {
        try {
            String[] parts=command.split("\\s+"); int page=parts.length>1?Integer.parseInt(parts[1])-1:0;
            var records=ticketIssuance.customerTickets(telegramUserId,10,page);
            if(records.isEmpty()) { send(chatId,"tickets.empty",language,mainMenu(language)); return; }
            StringBuilder response=new StringBuilder(translations.text("tickets.header",language));
            for(var ticket:records) response.append("\n\n").append(translations.text("tickets.item",language,ticket.number(),ticket.campaignTitle(),ticket.orderReference(),ticket.status(),formatDate(ticket.drawAt(),language)));
            response.append("\n\n").append(translations.text("tickets.page",language,page+1,page+2));
            telegram.sendMessage(chatId,response.toString(),mainMenu(language));
        } catch(RuntimeException invalid){send(chatId,"tickets.page_invalid",language,mainMenu(language));}
    }

    private void quoteOrder(String text, long telegramUserId, long chatId, String language) {
        String[] fields = text.split("\\s+");
        if (fields.length != 3) {
            send(chatId, "order.usage", language, mainMenu(language));
            return;
        }
        try {
            OrderService.OrderQuote quote = orders.quote(telegramUserId, Long.parseLong(fields[1]), Integer.parseInt(fields[2]));
            telegram.sendMessage(chatId, translations.text("order.quote", language, quote.quantity(), quote.unitPrice(), quote.total(), quote.currency()), mainMenu(language));
        } catch (RuntimeException failure) {
            telegram.sendMessage(chatId, translations.text("order.failed", language, failure.getMessage()), mainMenu(language));
        }
    }

    private void confirmOrder(long telegramUserId, long chatId, String language) {
        try {
            OrderService.OrderSummary order = orders.confirm(telegramUserId);
            StringBuilder message = new StringBuilder(translations.text("order.created", language, order.reference(), order.quantity(), order.unitPrice(), order.total(), order.currency()));
            var instructions = orders.paymentInstructions(language);
            if (instructions.isEmpty()) {
                message.append("\n\n").append(translations.text("payment.not_configured", language));
            } else {
                for (var instruction : instructions) message.append("\n\n").append(instruction.title()).append("\n").append(instruction.instructions());
                message.append("\n\n").append(translations.text("payment.receipt_prompt", language, order.reference()));
            }
            telegram.sendMessage(chatId, message.toString(), mainMenu(language));
        } catch (RuntimeException failure) {
            telegram.sendMessage(chatId, translations.text("order.failed", language, failure.getMessage()), mainMenu(language));
        }
    }

    private void handleReceipt(Map<?, ?> message, long telegramUserId, long chatId, String language, String caption) {
        String[] command = caption.trim().split("\\s+");
        if (command.length != 2 || !"/receipt".equalsIgnoreCase(command[0])) {
            send(chatId, "receipt.caption_required", language, mainMenu(language));
            return;
        }
        try {
            String fileId; long size; String mime;
            Object photoValue = message.get("photo");
            if (photoValue instanceof List<?> photos && !photos.isEmpty()) {
                Map<?, ?> photo = map(photos.get(photos.size() - 1));
                fileId = string(photo.get("file_id")); size = number(photo.get("file_size"), 0); mime = "image/jpeg";
            } else {
                Map<?, ?> document = map(message.get("document"));
                fileId = string(document.get("file_id")); size = number(document.get("file_size"), 0); mime = string(document.get("mime_type"));
            }
            int submission = orders.submitReceipt(telegramUserId, command[1], fileId, mime, size);
            telegram.sendMessage(chatId, translations.text("receipt.submitted", language, command[1], submission), mainMenu(language));
        } catch (RuntimeException failure) {
            telegram.sendMessage(chatId, translations.text("receipt.failed", language, failure.getMessage()), mainMenu(language));
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
    private static long number(Object value, long fallback) { return value instanceof Number n ? n.longValue() : fallback; }
    private static Map<?, ?> map(Object value) { return value instanceof Map<?, ?> result ? result : Map.of(); }
}
