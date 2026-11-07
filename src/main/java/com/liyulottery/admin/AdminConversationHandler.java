package com.liyulottery.admin;

import com.liyulottery.campaign.CampaignAdminService;
import com.liyulottery.campaign.CampaignAdminService.CampaignTranslation;
import com.liyulottery.campaign.CampaignAdminService.NewCampaign;
import com.liyulottery.campaign.CampaignAdminService.NewPrize;
import com.liyulottery.campaign.CampaignAdminService.PrizeTranslation;
import com.liyulottery.common.TranslationService;
import com.liyulottery.common.EthiopianCalendarService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Comparator;
import java.util.Set;

@Service
@ConditionalOnProperty(prefix = "app.telegram", name = "enabled", havingValue = "true")
public class AdminConversationHandler {
    private static final long MAX_IMAGE_BYTES = 10L * 1024 * 1024;
    private static final Set<String> IMAGE_MIME_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
    private static final List<String> FIELD_KEYS = List.of("code", "price", "start", "close", "draw", "limits", "translation_en", "translation_am", "translation_ti");
    private static final List<String> PRIZE_FIELD_KEYS = List.of("category", "quantity", "order", "translation_en", "translation_am", "translation_ti", "value", "image_file_id");
    private final AdminConversationStore conversations;
    private final AdminAuthorizationService authorization;
    private final CampaignAdminService campaigns;
    private final TranslationService translations;
    private final EthiopianCalendarService calendar;

    public AdminConversationHandler(AdminConversationStore conversations, AdminAuthorizationService authorization,
                                    CampaignAdminService campaigns, TranslationService translations,
                                    EthiopianCalendarService calendar) {
        this.conversations = conversations;
        this.authorization = authorization;
        this.campaigns = campaigns;
        this.translations = translations;
        this.calendar = calendar;
    }

    public Optional<AdminReply> handle(long telegramUserId, String text, String language) {
        Optional<AdminConversationStore.AdminConversation> saved = conversations.find(telegramUserId);
        if (saved.isPresent()) {
            try {
                authorization.requireRole(telegramUserId, AdminRole.CAMPAIGN_MANAGER);
                if (saved.get().type().startsWith("CONFIRM_")) {
                    return Optional.of(continueConfirmation(telegramUserId, text, language, saved.get()));
                }
                return Optional.of(continueCampaignForm(telegramUserId, text, language, saved.get()));
            } catch (AccessDeniedException denied) {
                conversations.clear(telegramUserId);
                return Optional.of(reply("admin.denied", language));
            } catch (IllegalArgumentException | IllegalStateException invalid) {
                return Optional.of(new AdminReply("Not saved: " + invalid.getMessage(), keyboard(language)));
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

    public Optional<AdminReply> handleImage(long telegramUserId, Object photoValue, Object documentValue, String language) {
        Optional<AdminConversationStore.AdminConversation> saved = conversations.find(telegramUserId);
        if (saved.isEmpty() || !(saved.get().type().equals("WAIT_CAMPAIGN_IMAGE") || saved.get().type().equals("WAIT_PRIZE_IMAGE"))) {
            return Optional.empty();
        }
        try {
            authorization.requireRole(telegramUserId, AdminRole.CAMPAIGN_MANAGER);
            Map<?, ?> image;
            String mime;
            if (documentValue instanceof Map<?, ?> document && !document.isEmpty()) {
                image = document;
                mime = string(document.get("mime_type"));
            } else if (photoValue instanceof List<?> photos && !photos.isEmpty()) {
                image = photos.stream().filter(Map.class::isInstance).map(Map.class::cast)
                        .max(Comparator.comparingLong(candidate -> number(candidate.get("file_size"), -1))).orElse(Map.of());
                mime = "image/jpeg";
            } else {
                throw new IllegalArgumentException("Send a Telegram photo or an image document");
            }
            String fileId = string(image.get("file_id"));
            long size = number(image.get("file_size"), -1);
            if (!IMAGE_MIME_TYPES.contains(mime.toLowerCase(java.util.Locale.ROOT))) {
                throw new IllegalArgumentException("Use a JPEG, PNG, or WebP image");
            }
            if (fileId.isBlank() || size < 1 || size > MAX_IMAGE_BYTES) {
                throw new IllegalArgumentException("Image metadata must include a file ID and a size from 1 byte to 10 MiB");
            }
            String id = saved.get().data().get("id");
            String action = saved.get().type().equals("WAIT_CAMPAIGN_IMAGE") ? "CAMPAIGN_IMAGE" : "PRIZE_IMAGE";
            return Optional.of(stage(telegramUserId, action, Map.of("id", id, "file_id", fileId),
                    "Attach this image to " + (action.equals("CAMPAIGN_IMAGE") ? "campaign" : "prize") + " #" + id + "?"));
        } catch (AccessDeniedException denied) {
            conversations.clear(telegramUserId);
            return Optional.of(reply("admin.denied", language));
        } catch (IllegalArgumentException | IllegalStateException invalid) {
            return Optional.of(new AdminReply(invalid.getMessage(), keyboard(language)));
        }
    }

    private AdminReply handleCommand(long telegramUserId, String text, String language) {
        String[] args = text.trim().split("\\s+");
        String command = args[0].split("@", 2)[0].toLowerCase(java.util.Locale.ROOT);
        if (command.equals("/admin")) {
            return new AdminReply("Administration commands:\n/campaigns\n/campaignnew\n/campaignprice ID AMOUNT CURRENCY\n/campaigntranslation ID LANG TITLE | DESCRIPTION | TERMS\n/campaigndates ID | START EC TIME | CLOSE EC TIME | DRAW EC TIME | TICKET_LIMIT | PER_USER_LIMIT\n/campaignimage ID (then send an image)\n/campaignpublish ID\n/campaignopen ID\n/campaignclose ID\n/campaigncancel ID\n/prizeadd CAMPAIGN_ID\n/prizeactive ID true|false\n/prizereorder ID ORDER\n/prizequantity ID QUANTITY\n/prizeimage ID (then send an image)\n/prizetranslation ID LANG NAME | DESCRIPTION\nConfirm important changes with /confirm. Use YYYY-MM-DD EC HH:mm for dates. Use /cancel during a pending action.", keyboard(language));
        }
        if (command.equals("/campaigns")) {
            List<CampaignAdminService.CampaignSummary> summaries = campaigns.listCampaigns(telegramUserId);
            if (summaries.isEmpty()) return new AdminReply("No campaigns have been created.", keyboard(language));
            StringBuilder result = new StringBuilder("Campaigns (latest 50):");
            summaries.forEach(item -> result.append("\n#").append(item.id()).append(" ").append(item.code()).append(" — ")
                    .append(item.status()).append(" — ").append(item.ticketPrice().toPlainString()).append(" ").append(item.currency()));
            summaries.forEach(item -> result.append("\n  Opens: ").append(calendar.format(item.salesStartAt()))
                    .append("\n  Closes: ").append(calendar.format(item.salesCloseAt()))
                    .append("\n  Draw: ").append(calendar.format(item.drawAt())));
            return new AdminReply(result.toString(), keyboard(language));
        }
        if (command.equals("/campaignnew")) {
            conversations.save(telegramUserId, 0, Map.of());
            return prompt(0, language);
        }
        if (command.equals("/campaignprice") && args.length == 4) {
            return stage(telegramUserId, "CAMPAIGN_PRICE", Map.of("id", args[1], "price", args[2], "currency", args[3].toUpperCase(java.util.Locale.ROOT)),
                    "Change campaign #" + args[1] + " ticket price to " + args[2] + " " + args[3].toUpperCase(java.util.Locale.ROOT) + "?");
        }
        if (command.equals("/campaigntranslation")) {
            String[] fields = text.trim().split("\\s+", 4);
            if (fields.length != 4) throw new IllegalArgumentException("Usage: /campaigntranslation ID en|am|ti title | description | approved terms");
            return stage(telegramUserId, "CAMPAIGN_TRANSLATION", Map.of("id", fields[1], "language", fields[2].toLowerCase(java.util.Locale.ROOT), "translation", fields[3]),
                    "Update campaign #" + fields[1] + " text in " + fields[2].toLowerCase(java.util.Locale.ROOT) + "?\n" + fields[3]);
        }
        if (command.equals("/campaigndates")) {
            String[] fields = text.trim().split("\\s+", 2);
            if (fields.length != 2) throw new IllegalArgumentException("Usage: /campaigndates ID START EC TIME | CLOSE EC TIME | DRAW EC TIME | TICKET_LIMIT | PER_USER_LIMIT");
            String[] values = fields[1].split("\\s*\\|\\s*", -1);
            if (values.length != 6) throw new IllegalArgumentException("Enter ID | start | close | draw | ticket limit | per-user limit; dates must be YYYY-MM-DD EC HH:mm");
            calendar.parseDateTime(values[1]); calendar.parseDateTime(values[2]); calendar.parseDateTime(values[3]);
            Map<String, String> data = Map.of("id", values[0].trim(), "start", values[1].trim(), "close", values[2].trim(),
                    "draw", values[3].trim(), "ticket_limit", values[4].trim(), "per_user_limit", values[5].trim());
            return stage(telegramUserId, "CAMPAIGN_DATES", data, "Update campaign #" + values[0].trim() + " schedule?\nOpens: " + calendar.format(calendar.parseDateTime(values[1]))
                    + "\nCloses: " + calendar.format(calendar.parseDateTime(values[2])) + "\nDraw: " + calendar.format(calendar.parseDateTime(values[3])));
        }
        if (command.equals("/campaignpublish") && args.length == 2) {
            return stage(telegramUserId, "CAMPAIGN_PUBLISH", Map.of("id", args[1]), "Publish campaign #" + args[1] + " as scheduled?");
        }
        if (command.equals("/campaignopen") && args.length == 2) {
            return stage(telegramUserId, "CAMPAIGN_OPEN", Map.of("id", args[1]), "Open sales for campaign #" + args[1] + "?");
        }
        if (command.equals("/campaignclose") && args.length == 2) {
            return stage(telegramUserId, "CAMPAIGN_CLOSE", Map.of("id", args[1]), "Close sales for campaign #" + args[1] + "?");
        }
        if (command.equals("/campaigncancel") && args.length == 2) {
            return stage(telegramUserId, "CAMPAIGN_CANCEL", Map.of("id", args[1]), "Cancel campaign #" + args[1] + "?");
        }
        if (command.equals("/prizeadd") && args.length == 2) {
            Map<String, String> data = Map.of("campaign_id", args[1]);
            conversations.save(telegramUserId, "PRIZE_NEW", 0, data);
            return prizePrompt(0, language);
        }
        if (command.equals("/prizeactive") && args.length == 3) {
            if (!args[2].equalsIgnoreCase("true") && !args[2].equalsIgnoreCase("false")) throw new IllegalArgumentException("Use true or false for the active flag");
            return stage(telegramUserId, "PRIZE_ACTIVE", Map.of("id", args[1], "active", args[2].toLowerCase(java.util.Locale.ROOT)),
                    "Set prize #" + args[1] + " active=" + args[2].toLowerCase(java.util.Locale.ROOT) + "?");
        }
        if (command.equals("/prizereorder") && args.length == 3) {
            Integer.parseInt(args[2]);
            return stage(telegramUserId, "PRIZE_ORDER", Map.of("id", args[1], "order", args[2]), "Set prize #" + args[1] + " display order to " + args[2] + "?");
        }
        if (command.equals("/prizequantity") && args.length == 3) {
            if (Integer.parseInt(args[2]) < 1) throw new IllegalArgumentException("Quantity must be positive");
            return stage(telegramUserId, "PRIZE_QUANTITY", Map.of("id", args[1], "quantity", args[2]), "Set prize #" + args[1] + " quantity to " + args[2] + "?");
        }
        if (command.equals("/campaignimage") && args.length == 2) {
            conversations.save(telegramUserId, "WAIT_CAMPAIGN_IMAGE", 0, Map.of("id", args[1]));
            return new AdminReply("Send the campaign image as a photo or image document (maximum 10 MiB). Send /cancel to stop.", keyboard(language));
        }
        if (command.equals("/campaignimage") && args.length == 3 && args[2].equalsIgnoreCase("remove")) {
            return stage(telegramUserId, "CAMPAIGN_IMAGE_REMOVE", Map.of("id", args[1]), "Remove the campaign image from campaign #" + args[1] + "?");
        }
        if (command.equals("/prizeimage") && args.length == 2) {
            conversations.save(telegramUserId, "WAIT_PRIZE_IMAGE", 0, Map.of("id", args[1]));
            return new AdminReply("Send the prize image as a photo or image document (maximum 10 MiB). Send /cancel to stop.", keyboard(language));
        }
        if (command.equals("/prizeimage") && args.length == 3 && args[2].equalsIgnoreCase("remove")) {
            return stage(telegramUserId, "PRIZE_IMAGE_REMOVE", Map.of("id", args[1]), "Remove the image from prize #" + args[1] + "?");
        }
        if (command.equals("/prizetranslation")) {
            String[] fields = text.trim().split("\\s+", 4);
            if (fields.length != 4) throw new IllegalArgumentException("Usage: /prizetranslation ID en|am|ti name | description");
            return stage(telegramUserId, "PRIZE_TRANSLATION", Map.of("id", fields[1], "language", fields[2].toLowerCase(java.util.Locale.ROOT), "translation", fields[3]),
                    "Update prize #" + fields[1] + " text in " + fields[2].toLowerCase(java.util.Locale.ROOT) + "?\n" + fields[3]);
        }
        return new AdminReply("Use /admin to view available campaign commands.", keyboard(language));
    }

    private AdminReply continueCampaignForm(long telegramUserId, String text, String language, AdminConversationStore.AdminConversation state) {
        if (state.type().startsWith("WAIT_")) {
            if (text.trim().equalsIgnoreCase("/cancel")) {
                conversations.clear(telegramUserId);
                return new AdminReply("Image update cancelled.", keyboard(language));
            }
            return new AdminReply("Send an image, or /cancel to stop.", keyboard(language));
        }
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
                return stage(telegramUserId, "CAMPAIGN_CREATE", data, "Create this campaign draft with the supplied three-language text and schedule? Review all values, then send /confirm.");
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
                return stage(telegramUserId, "PRIZE_CREATE", data, "Create this prize with the supplied three-language names, quantity, order, value, and image reference? Review and send /confirm.");
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
                price[1].toUpperCase(java.util.Locale.ROOT), calendar.parseDateTime(data.get("start")),
                calendar.parseDateTime(data.get("close")), calendar.parseDateTime(data.get("draw")),
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

    private void validateStep(int step, String input) {
        switch (step) {
            case 0 -> { if (input.length() < 3 || input.length() > 40) throw new IllegalArgumentException("code must be 3–40 characters"); }
            case 1 -> { String[] p = input.split("\\s+"); if (p.length != 2 || new BigDecimal(p[0]).signum() <= 0 || !p[1].matches("[A-Za-z]{3}")) throw new IllegalArgumentException("enter a positive amount and currency, for example 25 ETB"); }
            case 2, 3, 4 -> calendar.parseDateTime(input);
            case 5 -> { String[] p = input.split("\\|", -1); if (p.length != 2 || Long.parseLong(p[0].trim()) < 0 || Integer.parseInt(p[1].trim()) < 0) throw new IllegalArgumentException("enter non-negative limits as ticket-limit|per-user-limit; use 0 for no limit"); }
            case 6, 7, 8 -> { String[] p = input.split("\\|", -1); if (p.length != 3 || p[0].isBlank() || p[1].isBlank() || p[2].isBlank()) throw new IllegalArgumentException("enter title | description | approved terms"); }
            default -> throw new IllegalArgumentException("form step is not recognized");
        }
    }

    private AdminReply prompt(int step, String language) { return new AdminReply(promptText(step), keyboard(language)); }

    private AdminReply prizePrompt(int step, String language) { return new AdminReply(prizePromptText(step), keyboard(language)); }

    private AdminReply stage(long telegramUserId, String action, Map<String, String> data, String preview) {
        conversations.save(telegramUserId, "CONFIRM_" + action, 0, data);
        return new AdminReply(preview + "\nSend /confirm to save or /cancel to discard.", keyboard("en"));
    }

    private AdminReply continueConfirmation(long telegramUserId, String text, String language,
                                           AdminConversationStore.AdminConversation state) {
        String answer = text.trim();
        if (answer.equalsIgnoreCase("/cancel")) {
            conversations.clear(telegramUserId);
            return new AdminReply("Change cancelled; nothing was saved.", keyboard(language));
        }
        if (!answer.equalsIgnoreCase("/confirm")) {
            return new AdminReply("Nothing has been saved. Send /confirm to apply this change or /cancel to discard it.", keyboard(language));
        }
        Map<String, String> data = state.data();
        long id = data.containsKey("id") ? Long.parseLong(data.get("id")) : -1;
        switch (state.type()) {
            case "CONFIRM_CAMPAIGN_PRICE" -> campaigns.updateDraftPrice(telegramUserId, id,
                    new BigDecimal(data.get("price")), data.get("currency"));
            case "CONFIRM_CAMPAIGN_TRANSLATION" -> campaigns.updateDraftTranslation(telegramUserId, id,
                    data.get("language"), translation(data.get("translation")));
            case "CONFIRM_CAMPAIGN_DATES" -> campaigns.updateDraftSchedule(telegramUserId, id,
                    calendar.parseDateTime(data.get("start")), calendar.parseDateTime(data.get("close")),
                    calendar.parseDateTime(data.get("draw")), limit(data.get("ticket_limit")), intLimit(data.get("per_user_limit")));
            case "CONFIRM_CAMPAIGN_PUBLISH" -> campaigns.publish(telegramUserId, id);
            case "CONFIRM_CAMPAIGN_OPEN" -> campaigns.openSales(telegramUserId, id);
            case "CONFIRM_CAMPAIGN_CLOSE" -> campaigns.closeSales(telegramUserId, id);
            case "CONFIRM_CAMPAIGN_CANCEL" -> campaigns.cancel(telegramUserId, id);
            case "CONFIRM_PRIZE_ACTIVE" -> campaigns.setPrizeActive(telegramUserId, id, Boolean.parseBoolean(data.get("active")));
            case "CONFIRM_PRIZE_ORDER" -> campaigns.reorderPrize(telegramUserId, id, Integer.parseInt(data.get("order")));
            case "CONFIRM_PRIZE_QUANTITY" -> campaigns.updatePrizeQuantity(telegramUserId, id, Integer.parseInt(data.get("quantity")));
            case "CONFIRM_PRIZE_TRANSLATION" -> campaigns.updatePrizeTranslation(telegramUserId, id,
                    data.get("language"), prizeTranslation(data.get("translation")));
            case "CONFIRM_CAMPAIGN_IMAGE_REMOVE" -> campaigns.updateCampaignImage(telegramUserId, id, null);
            case "CONFIRM_PRIZE_IMAGE_REMOVE" -> campaigns.updatePrizeImage(telegramUserId, id, null);
            case "CONFIRM_CAMPAIGN_IMAGE" -> campaigns.updateCampaignImage(telegramUserId, id, data.get("file_id"));
            case "CONFIRM_PRIZE_IMAGE" -> campaigns.updatePrizeImage(telegramUserId, id, data.get("file_id"));
            case "CONFIRM_CAMPAIGN_CREATE" -> {
                long created = campaigns.createDraft(telegramUserId, toCampaign(data));
                conversations.clear(telegramUserId);
                return new AdminReply("Draft campaign created with ID " + created + ". Review it with /campaigns, then publish when ready.", keyboard(language));
            }
            case "CONFIRM_PRIZE_CREATE" -> {
                long created = campaigns.addPrize(telegramUserId, Long.parseLong(data.get("campaign_id")), toPrize(data));
                conversations.clear(telegramUserId);
                return new AdminReply("Prize created with ID " + created + ".", keyboard(language));
            }
            default -> throw new IllegalStateException("This pending confirmation is not supported");
        }
        conversations.clear(telegramUserId);
        return new AdminReply("Confirmed and saved.", keyboard(language));
    }

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
            case 2 -> "Sales start (Ethiopian Calendar): YYYY-MM-DD EC HH:mm, for example 2019-02-15 EC 14:30:";
            case 3 -> "Sales close (Ethiopian Calendar): YYYY-MM-DD EC HH:mm:";
            case 4 -> "Draw date and time (Ethiopian Calendar): YYYY-MM-DD EC HH:mm:";
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

    private static String string(Object value) { return value == null ? "" : value.toString(); }
    private static long number(Object value, long fallback) { return value instanceof Number number ? number.longValue() : fallback; }

    private static boolean isAdminCommand(String text) {
        return text != null && (text.startsWith("/admin") || text.startsWith("/campaign") || text.startsWith("/prize"));
    }

    public record AdminReply(String text, Map<String, Object> replyMarkup) { }
}
