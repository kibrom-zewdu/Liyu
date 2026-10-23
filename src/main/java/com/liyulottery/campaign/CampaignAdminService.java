package com.liyulottery.campaign;

import com.liyulottery.admin.AdminAuthorizationService;
import com.liyulottery.admin.AdminRole;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class CampaignAdminService {
    private static final Pattern CODE = Pattern.compile("[A-Z0-9][A-Z0-9_-]{2,39}");
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");
    private static final Set<String> LANGUAGES = Set.of("en", "am", "ti");
    private final JdbcTemplate jdbcTemplate;
    private final AdminAuthorizationService authorization;

    public CampaignAdminService(JdbcTemplate jdbcTemplate, AdminAuthorizationService authorization) {
        this.jdbcTemplate = jdbcTemplate;
        this.authorization = authorization;
    }

    @Transactional
    public long createDraft(long actorTelegramId, NewCampaign campaign) {
        long actor = authorization.requireRole(actorTelegramId, AdminRole.CAMPAIGN_MANAGER);
        validate(campaign);
        jdbcTemplate.update("""
                INSERT INTO campaigns(campaign_code,ticket_price,currency,sales_start_at,sales_close_at,draw_at,
                    ticket_limit,per_user_limit,status,created_by_admin_id,updated_by_admin_id)
                VALUES(?,?,?,?,?,?,?,?,'DRAFT',?,?)
                """, campaign.code(), campaign.ticketPrice(), campaign.currency(), timestamp(campaign.salesStartAt()),
                timestamp(campaign.salesCloseAt()), timestamp(campaign.drawAt()), campaign.ticketLimit(),
                campaign.perUserLimit(), actor, actor);
        Long campaignId = jdbcTemplate.queryForObject("SELECT id FROM campaigns WHERE campaign_code=?", Long.class, campaign.code());
        campaign.translations().forEach((language, translation) -> jdbcTemplate.update(
                "INSERT INTO campaign_translations(campaign_id,language_code,title,description,terms) VALUES(?,?,?,?,?)",
                campaignId, language, translation.title(), translation.description(), translation.terms()));
        audit(actor, "CAMPAIGN_CREATED", "campaign", campaignId, null, campaign.code());
        return campaignId;
    }

    @Transactional
    public void updateDraftPrice(long actorTelegramId, long campaignId, BigDecimal price, String currency) {
        long actor = authorization.requireRole(actorTelegramId, AdminRole.CAMPAIGN_MANAGER);
        if (price == null || price.signum() <= 0 || currency == null || !CURRENCY.matcher(currency).matches()) {
            throw new IllegalArgumentException("Price must be positive and currency must be a three-letter uppercase code");
        }
        int changed = jdbcTemplate.update("UPDATE campaigns SET ticket_price=?,currency=?,updated_by_admin_id=? WHERE id=? AND status='DRAFT'",
                price, currency, actor, campaignId);
        if (changed != 1) throw new IllegalStateException("Only a draft campaign can be edited");
        audit(actor, "CAMPAIGN_PRICE_CHANGED", "campaign", campaignId, null, price + " " + currency);
    }

    @Transactional
    public void updateDraftTranslation(long actorTelegramId, long campaignId, String language, CampaignTranslation translation) {
        long actor = authorization.requireRole(actorTelegramId, AdminRole.CAMPAIGN_MANAGER);
        if (!LANGUAGES.contains(language) || translation == null || blank(translation.title())
                || blank(translation.description()) || blank(translation.terms())) {
            throw new IllegalArgumentException("Enter a supported language and non-empty title, description, and approved terms");
        }
        Integer draft = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM campaigns WHERE id=? AND status='DRAFT'", Integer.class, campaignId);
        if (draft == null || draft != 1) throw new IllegalStateException("Only a draft campaign can be edited");
        jdbcTemplate.update("""
                INSERT INTO campaign_translations(campaign_id,language_code,title,description,terms) VALUES(?,?,?,?,?)
                ON DUPLICATE KEY UPDATE title=VALUES(title),description=VALUES(description),terms=VALUES(terms)
                """, campaignId, language, translation.title().trim(), translation.description().trim(), translation.terms().trim());
        audit(actor, "CAMPAIGN_TRANSLATION_CHANGED", "campaign", campaignId, language, translation.title().trim());
    }

    @Transactional
    public void updateDraftSchedule(long actorTelegramId, long campaignId, Instant start, Instant close, Instant draw,
                                   Long ticketLimit, Integer perUserLimit) {
        long actor = authorization.requireRole(actorTelegramId, AdminRole.CAMPAIGN_MANAGER);
        if (start == null || close == null || draw == null || !start.isBefore(close) || !close.isBefore(draw)
                || (ticketLimit != null && ticketLimit < 1) || (perUserLimit != null && perUserLimit < 1)) {
            throw new IllegalArgumentException("Dates must be ordered start < close < draw; limits must be positive or unlimited");
        }
        int updated = jdbcTemplate.update("""
                UPDATE campaigns SET sales_start_at=?,sales_close_at=?,draw_at=?,ticket_limit=?,per_user_limit=?,updated_by_admin_id=?
                WHERE id=? AND status='DRAFT'
                """, timestamp(start), timestamp(close), timestamp(draw), ticketLimit, perUserLimit, actor, campaignId);
        if (updated != 1) throw new IllegalStateException("Only a draft campaign can be edited");
        audit(actor, "CAMPAIGN_SCHEDULE_CHANGED", "campaign", campaignId, null, start + "|" + close + "|" + draw);
    }

    @Transactional
    public void publish(long actorTelegramId, long campaignId) {
        long actor = authorization.requireRole(actorTelegramId, AdminRole.CAMPAIGN_MANAGER);
        CampaignStatus current = lockStatus(campaignId);
        requireTransition(current, CampaignStatus.SCHEDULED);
        Integer translationCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM campaign_translations WHERE campaign_id=? AND title<>'' AND description<>'' AND terms IS NOT NULL AND terms<>''", Integer.class, campaignId);
        if (translationCount == null || translationCount != LANGUAGES.size()) {
            throw new IllegalStateException("All three campaign translations are required before publishing");
        }
        Integer datesValid = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM campaigns WHERE id=? AND sales_start_at IS NOT NULL AND sales_close_at IS NOT NULL
                    AND draw_at IS NOT NULL AND sales_start_at<sales_close_at AND sales_close_at<draw_at
                """, Integer.class, campaignId);
        if (datesValid == null || datesValid != 1) throw new IllegalStateException("Sales and draw dates must be configured in chronological order");
        jdbcTemplate.update("UPDATE campaigns SET status='SCHEDULED',updated_by_admin_id=? WHERE id=?", actor, campaignId);
        audit(actor, "CAMPAIGN_PUBLISHED", "campaign", campaignId, current.name(), CampaignStatus.SCHEDULED.name());
    }

    @Transactional
    public void openSales(long actorTelegramId, long campaignId) {
        long actor = authorization.requireRole(actorTelegramId, AdminRole.CAMPAIGN_MANAGER);
        CampaignStatus current = lockStatus(campaignId);
        requireTransition(current, CampaignStatus.OPEN);
        Integer windowValid = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM campaigns WHERE id=? AND sales_start_at<=UTC_TIMESTAMP(6)
                    AND sales_close_at>UTC_TIMESTAMP(6)
                """, Integer.class, campaignId);
        if (windowValid == null || windowValid != 1) throw new IllegalStateException("Sales can only open within the configured sales window");
        jdbcTemplate.update("UPDATE campaigns SET status='OPEN',updated_by_admin_id=? WHERE id=?", actor, campaignId);
        audit(actor, "CAMPAIGN_SALES_OPENED", "campaign", campaignId, current.name(), CampaignStatus.OPEN.name());
    }

    @Transactional
    public void closeSales(long actorTelegramId, long campaignId) {
        transition(actorTelegramId, campaignId, CampaignStatus.CLOSED);
    }

    @Transactional
    public void cancel(long actorTelegramId, long campaignId) {
        transition(actorTelegramId, campaignId, CampaignStatus.CANCELLED);
    }

    @Transactional
    public long addPrize(long actorTelegramId, long campaignId, NewPrize prize) {
        long actor = authorization.requireRole(actorTelegramId, AdminRole.CAMPAIGN_MANAGER);
        validate(prize);
        String campaignStatus = jdbcTemplate.queryForObject("SELECT status FROM campaigns WHERE id=? FOR UPDATE", String.class, campaignId);
        if ("COMPLETED".equals(campaignStatus) || "CANCELLED".equals(campaignStatus)) {
            throw new IllegalStateException("Prizes cannot be changed for a completed or cancelled campaign");
        }
        jdbcTemplate.update("INSERT INTO prizes(campaign_id,category,quantity,monetary_value,image_file_id,display_order,active) VALUES(?,?,?,?,?,?,TRUE)",
                campaignId, prize.category(), prize.quantity(), prize.monetaryValue(), prize.imageFileId(), prize.displayOrder());
        Long prizeId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        prize.translations().forEach((language, translation) -> jdbcTemplate.update(
                "INSERT INTO prize_translations(prize_id,language_code,name,description) VALUES(?,?,?,?)",
                prizeId, language, translation.name(), translation.description()));
        audit(actor, "PRIZE_CREATED", "prize", prizeId, null, prize.translations().get("en").name());
        return prizeId;
    }

    @Transactional
    public void setPrizeActive(long actorTelegramId, long prizeId, boolean active) {
        long actor = authorization.requireRole(actorTelegramId, AdminRole.CAMPAIGN_MANAGER);
        assertPrizeMutable(prizeId);
        int changed = jdbcTemplate.update("UPDATE prizes SET active=?,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?", active, prizeId);
        if (changed != 1) throw new IllegalArgumentException("Prize was not found");
        audit(actor, active ? "PRIZE_ACTIVATED" : "PRIZE_DEACTIVATED", "prize", prizeId, null, Boolean.toString(active));
    }

    @Transactional
    public void reorderPrize(long actorTelegramId, long prizeId, int order) {
        long actor = authorization.requireRole(actorTelegramId, AdminRole.CAMPAIGN_MANAGER);
        if (order < 0) throw new IllegalArgumentException("Display order must not be negative");
        assertPrizeMutable(prizeId);
        int changed = jdbcTemplate.update("UPDATE prizes SET display_order=?,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?", order, prizeId);
        if (changed != 1) throw new IllegalArgumentException("Prize was not found");
        audit(actor, "PRIZE_REORDERED", "prize", prizeId, null, Integer.toString(order));
    }

    @Transactional
    public void updatePrizeTranslation(long actorTelegramId, long prizeId, String language, PrizeTranslation translation) {
        long actor = authorization.requireRole(actorTelegramId, AdminRole.CAMPAIGN_MANAGER);
        if (!LANGUAGES.contains(language) || translation == null || blank(translation.name())) {
            throw new IllegalArgumentException("Enter a supported language and a prize name");
        }
        assertPrizeMutable(prizeId);
        jdbcTemplate.update("""
                INSERT INTO prize_translations(prize_id,language_code,name,description) VALUES(?,?,?,?)
                ON DUPLICATE KEY UPDATE name=VALUES(name),description=VALUES(description)
                """, prizeId, language, translation.name().trim(), translation.description());
        audit(actor, "PRIZE_TRANSLATION_CHANGED", "prize", prizeId, language, translation.name().trim());
    }

    @Transactional(readOnly = true)
    public List<CampaignSummary> listCampaigns(long actorTelegramId) {
        authorization.requireRole(actorTelegramId, AdminRole.CAMPAIGN_MANAGER);
        return jdbcTemplate.query("SELECT id,campaign_code,status,ticket_price,currency FROM campaigns ORDER BY created_at DESC,id DESC LIMIT 50",
                (rs, row) -> new CampaignSummary(rs.getLong(1), rs.getString(2), CampaignStatus.valueOf(rs.getString(3)),
                        rs.getBigDecimal(4), rs.getString(5)));
    }

    private void transition(long actorTelegramId, long campaignId, CampaignStatus target) {
        long actor = authorization.requireRole(actorTelegramId, AdminRole.CAMPAIGN_MANAGER);
        CampaignStatus current = lockStatus(campaignId);
        requireTransition(current, target);
        jdbcTemplate.update("UPDATE campaigns SET status=?,updated_by_admin_id=? WHERE id=?", target.name(), actor, campaignId);
        audit(actor, "CAMPAIGN_STATUS_CHANGED", "campaign", campaignId, current.name(), target.name());
    }

    private CampaignStatus lockStatus(long campaignId) {
        String status = jdbcTemplate.queryForObject("SELECT status FROM campaigns WHERE id=? FOR UPDATE", String.class, campaignId);
        return CampaignStatus.valueOf(status);
    }

    private void assertPrizeMutable(long prizeId) {
        String status = jdbcTemplate.queryForObject("""
                SELECT c.status FROM campaigns c JOIN prizes p ON p.campaign_id=c.id WHERE p.id=? FOR UPDATE
                """, String.class, prizeId);
        if (status == null || status.equals("COMPLETED") || status.equals("CANCELLED")) {
            throw new IllegalStateException("Prize does not exist or its campaign is completed or cancelled");
        }
    }

    private static void requireTransition(CampaignStatus current, CampaignStatus target) {
        if (!current.canTransitionTo(target)) throw new IllegalStateException("Invalid campaign status transition");
    }

    private static void validate(NewCampaign campaign) {
        if (campaign == null || campaign.code() == null || !CODE.matcher(campaign.code()).matches()) {
            throw new IllegalArgumentException("Campaign code must be 3–40 uppercase letters, digits, underscores, or hyphens");
        }
        if (campaign.currency() == null || !CURRENCY.matcher(campaign.currency()).matches()
                || campaign.ticketPrice() == null || campaign.ticketPrice().signum() <= 0) {
            throw new IllegalArgumentException("Campaign requires a positive ticket price and a three-letter uppercase currency code");
        }
        if ((campaign.ticketLimit() != null && campaign.ticketLimit() < 1)
                || (campaign.perUserLimit() != null && campaign.perUserLimit() < 1)) {
            throw new IllegalArgumentException("Ticket limits must be positive");
        }
        if (campaign.translations() == null || campaign.translations().size() != LANGUAGES.size()
                || !campaign.translations().keySet().containsAll(LANGUAGES)) {
            throw new IllegalArgumentException("Provide campaign translations in English, Amharic, and Tigrigna");
        }
        campaign.translations().values().forEach(value -> {
            if (value == null || blank(value.title()) || blank(value.description())) throw new IllegalArgumentException("Campaign title and description are required in each language");
        });
        if (campaign.salesStartAt() != null && campaign.salesCloseAt() != null && !campaign.salesStartAt().isBefore(campaign.salesCloseAt())) {
            throw new IllegalArgumentException("Sales start must be before sales close");
        }
        if (campaign.salesCloseAt() != null && campaign.drawAt() != null && !campaign.salesCloseAt().isBefore(campaign.drawAt())) {
            throw new IllegalArgumentException("Sales must close before the draw date");
        }
    }

    private static void validate(NewPrize prize) {
        if (prize == null || prize.quantity() < 1 || prize.displayOrder() < 0 || prize.translations() == null
                || prize.translations().size() != LANGUAGES.size() || !prize.translations().keySet().containsAll(LANGUAGES)) {
            throw new IllegalArgumentException("Prize quantity, order, and all three language translations are required");
        }
        if (prize.monetaryValue() != null && prize.monetaryValue().signum() < 0) throw new IllegalArgumentException("Prize value cannot be negative");
        prize.translations().values().forEach(value -> {
            if (value == null || blank(value.name())) throw new IllegalArgumentException("Prize name is required in each language");
        });
    }

    private void audit(long actor, String action, String targetType, long targetId, String before, String after) {
        jdbcTemplate.update("""
                INSERT INTO audit_logs(actor_admin_id,action,target_type,target_id,before_json,after_json)
                VALUES(?,?,?,?,JSON_OBJECT('value',?),JSON_OBJECT('value',?))
                """, actor, action, targetType, Long.toString(targetId), before, after);
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }

    public record CampaignTranslation(String title, String description, String terms) { }
    public record PrizeTranslation(String name, String description) { }
    public record NewCampaign(String code, BigDecimal ticketPrice, String currency, Instant salesStartAt,
                              Instant salesCloseAt, Instant drawAt, Long ticketLimit, Integer perUserLimit,
                              Map<String, CampaignTranslation> translations) { }
    public record NewPrize(String category, int quantity, BigDecimal monetaryValue, String imageFileId,
                           int displayOrder, Map<String, PrizeTranslation> translations) { }
    public record CampaignSummary(long id, String code, CampaignStatus status, BigDecimal ticketPrice, String currency) { }
}
