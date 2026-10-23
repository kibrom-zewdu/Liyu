package com.liyulottery.admin;

import com.liyulottery.campaign.CampaignAdminService;
import com.liyulottery.campaign.CampaignAdminService.CampaignTranslation;
import com.liyulottery.campaign.CampaignAdminService.NewCampaign;
import com.liyulottery.campaign.CampaignAdminService.NewPrize;
import com.liyulottery.campaign.CampaignAdminService.PrizeTranslation;
import com.liyulottery.common.TranslationService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@ConditionalOnProperty(prefix = "app.telegram", name = "enabled", havingValue = "true")
public class AdminConversationHandler {
    private static final List<String> FIELD_KEYS = List.of("code", "price", "start", "close", "draw", "limits", "translation_en", "translation_am", "translation_ti");
    private static final List<String> PRIZE_FIELD_KEYS = List.of("category", "quantity", "order", "translation_en", "translation_am", "translation_ti", "value", "image_file_id");
    private final AdminConversationStore conversations;
    private final AdminAuthorizationService authorization;
    private final CampaignAdminService campaigns;
    private final TranslationService translations;

    public AdminConversationHandler(AdminConversationStore conversations, AdminAuthorizationService authorization,
                                    CampaignAdminService campaigns, TranslationService translations) {
        this.conversations = conversations;
        this.authorization = authorization;
        this.campaigns = campaigns;
        this.translations = translations;
    }

    public Optional<AdminReply> handle(long telegramUserId, String text, String language) {
        Optional<AdminConversationStore.AdminConversation> saved = conversations.find(telegramUserId);
        if (saved.isPresent()) {
            try {
                authorization.requireRole(telegramUserId, AdminRole.CAMPAIGN_MANAGER);
                return Optional.of(continueCampaignForm(telegramUserId, text, language, saved.get()));
            } catch (AccessDeniedException denied) {
                conversations.clear(telegramUserId);
                return Optional.of(reply("admin.denied", language));
            }
        }
        if (!isAdminCommand(text)) return Optional.empty();
        try {
            authorization.requireRole(telegramUserId, AdminRole.CAMPAIGN_MANAGER);
            return Optional.of(handleCommand(telegramUserId, text, language));
        } catch (AccessDeniedException denied) {
            return Optional.of(reply("admin.denied", language));
        } catch (IllegalArgumentException | IllegalStateException invalid) {
            return Optional.of(new AdminReply(invalid.getMessage(), keyboard(language)));
        }
    }

    private AdminReply handleCommand(long telegramUserId, String text, String language) {
        String[] args = text.trim().split("\\s+");
        String command = args[0].split("@", 2)[0].toLowerCase(java.util.Locale.ROOT);
        if (command.equals("/admin")) {
            return new AdminReply("Administration commands:\n/campaigns\n/campaignnew\n/campaignprice ID AMOUNT CURRENCY\n/campaigntranslation ID LANG TITLE | DESCRIPTION | TERMS\n/campaigndates ID START CLOSE DRAW TICKET_LIMIT PER_USER_LIMIT\n/campaignpublish ID\n/campaignopen ID\n/campaignclose ID\n/campaigncancel ID\n/prizeadd CAMPAIGN_ID\n/prizeactive ID true|false\n/prizereorder ID ORDER\n/prizetranslation ID LANG NAME | DESCRIPTION\nUse /cancel or Back during a form.", keyboard(language));
        }
        if (command.equals("/campaigns")) {
            List<CampaignAdminService.CampaignSummary> summaries = campaigns.listCampaigns(telegramUserId);
            if (summaries.isEmpty()) return new AdminReply("No campaigns have been created.", keyboard(language));
            StringBuilder result = new StringBuilder("Campaigns (latest 50):");
            summaries.forEach(item -> result.append("\n#").append(item.id()).append(" ").append(item.code()).append(" — ")
                    .append(item.status()).append(" — ").append(item.ticketPrice().toPlainString()).append(" ").append(item.currency()));
            return new AdminReply(result.toString(), keyboard(language));
        }
        if (command.equals("/campaignnew")) {
            conversations.save(telegramUserId, 0, Map.of());
            return prompt(0, language);
        }
        if (command.equals("/campaignprice") && args.length == 4) {
            campaigns.updateDraftPrice(telegramUserId, Long.parseLong(args[1]), new BigDecimal(args[2]), args[3].toUpperCase(java.util.Locale.ROOT));
            return new AdminReply("Draft campaign price updated.", keyboard(language));
        }
        if (command.equals("/campaigntranslation")) {
            String[] fields = text.trim().split("\\s+", 4);
            if (fields.length != 4) throw new IllegalArgumentException("Usage: /campaigntranslation ID en|am|ti title | description | approved terms");
            campaigns.updateDraftTranslation(telegramUserId, Long.parseLong(fields[1]), fields[2].toLowerCase(java.util.Locale.ROOT), translation(fields[3]));
            return new AdminReply("Draft campaign translation updated.", keyboard(language));
        }
        if (command.equals("/campaigndates") && args.length == 7) {
            campaigns.updateDraftSchedule(telegramUserId, Long.parseLong(args[1]), OffsetDateTime.parse(args[2]).toInstant(),
                    OffsetDateTime.parse(args[3]).toInstant(), OffsetDateTime.parse(args[4]).toInstant(), limit(args[5]), intLimit(args[6]));
            return new AdminReply("Draft campaign dates and limits updated.", keyboard(language));
        }
        if (command.equals("/campaignpublish") && args.length == 2) {
            campaigns.publish(telegramUserId, Long.parseLong(args[1]));
            return new AdminReply("Campaign published as SCHEDULED.", keyboard(language));
        }
        if (command.equals("/campaignopen") && args.length == 2) {
            campaigns.openSales(telegramUserId, Long.parseLong(args[1]));
            return new AdminReply("Campaign sales opened.", keyboard(language));
        }
        if (command.equals("/campaignclose") && args.length == 2) {
            campaigns.closeSales(telegramUserId, Long.parseLong(args[1]));
            return new AdminReply("Campaign sales closed.", keyboard(language));
        }
        if (command.equals("/campaigncancel") && args.length == 2) {
            campaigns.cancel(telegramUserId, Long.parseLong(args[1]));
            return new AdminReply("Campaign cancelled.", keyboard(language));
        }
        if (command.equals("/prizeadd") && args.length == 2) {
            Map<String, String> data = Map.of("campaign_id", args[1]);
            conversations.save(telegramUserId, "PRIZE_NEW", 0, data);
            return prizePrompt(0, language);
        }
        if (command.equals("/prizeactive") && args.length == 3) {
            if (!args[2].equalsIgnoreCase("true") && !args[2].equalsIgnoreCase("false")) throw new IllegalArgumentException("Use true or false for the active flag");
            campaigns.setPrizeActive(telegramUserId, Long.parseLong(args[1]), Boolean.parseBoolean(args[2]));
            return new AdminReply("Prize active status updated.", keyboard(language));
        }
        if (command.equals("/prizereorder") && args.length == 3) {
            campaigns.reorderPrize(telegramUserId, Long.parseLong(args[1]), Integer.parseInt(args[2]));
            return new AdminReply("Prize display order updated.", keyboard(language));
        }
        if (command.equals("/prizetranslation")) {
            String[] fields = text.trim().split("\\s+", 4);
            if (fields.length != 4) throw new IllegalArgumentException("Usage: /prizetranslation ID en|am|ti name | description");
            campaigns.updatePrizeTranslation(telegramUserId, Long.parseLong(fields[1]), fields[2].toLowerCase(java.util.Locale.ROOT), prizeTranslation(fields[3]));
            return new AdminReply("Prize translation updated.", keyboard(language));
        }
        return new AdminReply("Use /admin to view available campaign commands.", keyboard(language));
    }

    private AdminReply continueCampaignForm(long telegramUserId, String text, String language, AdminConversationStore.AdminConversation state) {
        if ("PRIZE_NEW".equals(state.type())) return continuePrizeForm(telegramUserId, text, language, state);
        if (!"CAMPAIGN_NEW".equals(state.type())) {
            conversations.clear(telegramUserId);
            return new AdminReply("The saved form is not supported and was cleared. Send /campaignnew to start again.", keyboard(language));
        }
        String input = text.trim();
        if (input.equalsIgnoreCase("/cancel") || input.equalsIgnoreCase(translations.text("admin.cancel", language))) {
            conversations.clear(telegramUserId);
            return new AdminReply("Campaign form cancelled.", keyboard(language));
        }
        if (input.equalsIgnoreCase("/back") || input.equalsIgnoreCase(translations.text("admin.back", language))) {
            int previous = Math.max(0, state.currentStep() - 1);
            Map<String, String> data = new HashMap<>(state.data());
            if (state.currentStep() > 0) data.remove(FIELD_KEYS.get(previous));
            conversations.save(telegramUserId, previous, data);
            return prompt(previous, language);
        }
        if (state.currentStep() >= FIELD_KEYS.size()) {
            conversations.clear(telegramUserId);
            return new AdminReply("The form state was invalid and has been cleared.", keyboard(language));
        }
        Map<String, String> data = new HashMap<>(state.data());
        try {
            validateStep(state.currentStep(), input);
            data.put(FIELD_KEYS.get(state.currentStep()), input);
            if (state.currentStep() == FIELD_KEYS.size() - 1) {
                long campaignId = campaigns.createDraft(telegramUserId, toCampaign(data));
                conversations.clear(telegramUserId);
                return new AdminReply("Draft campaign created with ID " + campaignId + ". Use /campaignpublish " + campaignId + " after review.", keyboard(language));
            }
            conversations.save(telegramUserId, state.currentStep() + 1, data);
            return prompt(state.currentStep() + 1, language);
        } catch (RuntimeException invalid) {
            return new AdminReply("Invalid value: " + invalid.getMessage() + "\n" + promptText(state.currentStep()), keyboard(language));
        }
    }

    private AdminReply continuePrizeForm(long telegramUserId, String text, String language, AdminConversationStore.AdminConversation state) {
        String input = text.trim();
        if (input.equalsIgnoreCase("/cancel") || input.equalsIgnoreCase(translations.text("admin.cancel", language))) {
            conversations.clear(telegramUserId);
            return new AdminReply("Prize form cancelled.", keyboard(language));
        }
        if (input.equalsIgnoreCase("/back") || input.equalsIgnoreCase(translations.text("admin.back", language))) {
            int previous = Math.max(0, state.currentStep() - 1);
            Map<String, String> data = new HashMap<>(state.data());
            if (state.currentStep() > 0) data.remove(PRIZE_FIELD_KEYS.get(previous));
            conversations.save(telegramUserId, "PRIZE_NEW", previous, data);
            return prizePrompt(previous, language);
        }
        try {
            validatePrizeStep(state.currentStep(), input);
            Map<String, String> data = new HashMap<>(state.data());
            data.put(PRIZE_FIELD_KEYS.get(state.currentStep()), input);
            if (state.currentStep() == PRIZE_FIELD_KEYS.size() - 1) {
                long prizeId = campaigns.addPrize(telegramUserId, Long.parseLong(data.get("campaign_id")), toPrize(data));
                conversations.clear(telegramUserId);
                return new AdminReply("Prize created with ID " + prizeId + ".", keyboard(language));
            }
            conversations.save(telegramUserId, "PRIZE_NEW", state.currentStep() + 1, data);
            return prizePrompt(state.currentStep() + 1, language);
        } catch (RuntimeException invalid) {
            return new AdminReply("Invalid value: " + invalid.getMessage() + "\n" + prizePromptText(state.currentStep()), keyboard(language));
        }
    }

    private NewCampaign toCampaign(Map<String, String> data) {
        String[] price = data.get("price").trim().split("\\s+");
        String[] limits = data.get("limits").trim().split("\\|", -1);
        Map<String, CampaignTranslation> campaignTranslations = new HashMap<>();
        campaignTranslations.put("en", translation(data.get("translation_en")));
        campaignTranslations.put("am", translation(data.get("translation_am")));
        campaignTranslations.put("ti", translation(data.get("translation_ti")));
        return new NewCampaign(data.get("code").trim().toUpperCase(java.util.Locale.ROOT), new BigDecimal(price[0]),
                price[1].toUpperCase(java.util.Locale.ROOT), OffsetDateTime.parse(data.get("start")).toInstant(),
                OffsetDateTime.parse(data.get("close")).toInstant(), OffsetDateTime.parse(data.get("draw")).toInstant(),
                limit(limits[0]), intLimit(limits[1]), campaignTranslations);
    }

    private static CampaignTranslation translation(String value) {
        String[] parts = value.split("\\|", -1);
        if (parts.length != 3) throw new IllegalArgumentException("Enter title | description | approved terms");
        return new CampaignTranslation(parts[0].trim(), parts[1].trim(), parts[2].trim());
    }

    private static PrizeTranslation prizeTranslation(String value) {
        String[] parts = value.split("\\|", -1);
        if (parts.length != 2 || parts[0].isBlank()) throw new IllegalArgumentException("Enter prize name | description");
        return new PrizeTranslation(parts[0].trim(), parts[1].trim());
    }

    private static NewPrize toPrize(Map<String, String> data) {
        Map<String, PrizeTranslation> translations = Map.of(
                "en", prizeTranslation(data.get("translation_en")),
                "am", prizeTranslation(data.get("translation_am")),
                "ti", prizeTranslation(data.get("translation_ti")));
        String value = data.get("value").trim();
        return new NewPrize("-".equals(data.get("category")) ? null : data.get("category"),
                Integer.parseInt(data.get("quantity")), "none".equalsIgnoreCase(value) ? null : new BigDecimal(value),
                "none".equalsIgnoreCase(data.get("image_file_id")) ? null : data.get("image_file_id"),
                Integer.parseInt(data.get("order")), translations);
    }

    private static void validatePrizeStep(int step, String input) {
        switch (step) {
            case 0 -> { if (input.length() > 80) throw new IllegalArgumentException("category must be 80 characters or fewer; use - if none"); }
            case 1 -> { if (Integer.parseInt(input) < 1) throw new IllegalArgumentException("quantity must be positive"); }
            case 2 -> { if (Integer.parseInt(input) < 0) throw new IllegalArgumentException("display order cannot be negative"); }
            case 3, 4, 5 -> prizeTranslation(input);
            case 6 -> { if (!"none".equalsIgnoreCase(input) && new BigDecimal(input).signum() < 0) throw new IllegalArgumentException("value must be non-negative or none"); }
            case 7 -> { if (input.isBlank()) throw new IllegalArgumentException("enter a private Telegram file_id or none"); }
            default -> throw new IllegalArgumentException("form step is not recognized");
        }
    }

    private static Long limit(String value) {
        long parsed = Long.parseLong(value.trim());
        return parsed == 0 ? null : parsed;
    }

    private static Integer intLimit(String value) {
        int parsed = Integer.parseInt(value.trim());
        return parsed == 0 ? null : parsed;
    }

    private static void validateStep(int step, String input) {
        switch (step) {
            case 0 -> { if (input.length() < 3 || input.length() > 40) throw new IllegalArgumentException("code must be 3–40 characters"); }
            case 1 -> { String[] p = input.split("\\s+"); if (p.length != 2 || new BigDecimal(p[0]).signum() <= 0 || !p[1].matches("[A-Za-z]{3}")) throw new IllegalArgumentException("enter a positive amount and currency, for example 25 ETB"); }
            case 2, 3, 4 -> OffsetDateTime.parse(input);
            case 5 -> { String[] p = input.split("\\|", -1); if (p.length != 2 || Long.parseLong(p[0].trim()) < 0 || Integer.parseInt(p[1].trim()) < 0) throw new IllegalArgumentException("enter non-negative limits as ticket-limit|per-user-limit; use 0 for no limit"); }
            case 6, 7, 8 -> { String[] p = input.split("\\|", -1); if (p.length != 3 || p[0].isBlank() || p[1].isBlank() || p[2].isBlank()) throw new IllegalArgumentException("enter title | description | approved terms"); }
            default -> throw new IllegalArgumentException("form step is not recognized");
        }
    }

    private AdminReply prompt(int step, String language) { return new AdminReply(promptText(step), keyboard(language)); }

    private AdminReply prizePrompt(int step, String language) { return new AdminReply(prizePromptText(step), keyboard(language)); }

    private static String prizePromptText(int step) {
        return switch (step) {
            case 0 -> "Prize category (or -):";
            case 1 -> "Quantity available (positive integer):";
            case 2 -> "Display order (zero or greater):";
            case 3 -> "English prize name | description:";
            case 4 -> "Amharic prize name | description (native review required):";
            case 5 -> "Tigrigna prize name | description (native review required):";
            case 6 -> "Approved monetary value, or none:";
            case 7 -> "Private Telegram image file_id, or none:";
            default -> "Form complete.";
        };
    }

    private static String promptText(int step) {
        return switch (step) {
            case 0 -> "Campaign code (3–40 letters/digits, _ or -, uppercase preferred):";
            case 1 -> "Ticket price and currency (example: 25 ETB):";
            case 2 -> "Sales start as ISO date-time with timezone (example: 2026-12-01T09:00:00+03:00):";
            case 3 -> "Sales close as ISO date-time with timezone:";
            case 4 -> "Draw date as ISO date-time with timezone:";
            case 5 -> "Ticket limit|per-user limit; enter 0 for unlimited (example: 5000|20):";
            case 6 -> "English title | description | approved terms:";
            case 7 -> "Amharic title | description | approved terms (native review required):";
            case 8 -> "Tigrigna title | description | approved terms (native review required):";
            default -> "Form complete.";
        };
    }

    private AdminReply reply(String key, String language) { return new AdminReply(translations.text(key, language), keyboard(language)); }

    private Map<String, Object> keyboard(String language) {
        String safe = language == null ? "en" : language;
        return Map.of("keyboard", List.of(List.of(translations.text("admin.back", safe), translations.text("admin.cancel", safe))),
                "resize_keyboard", true, "is_persistent", true);
    }

    private static boolean isAdminCommand(String text) {
        return text != null && (text.startsWith("/admin") || text.startsWith("/campaign") || text.startsWith("/prize"));
    }

    public record AdminReply(String text, Map<String, Object> replyMarkup) { }
}
