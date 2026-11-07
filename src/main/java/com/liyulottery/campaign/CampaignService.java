package com.liyulottery.campaign;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

@Service
public class CampaignService {
    private final JdbcTemplate jdbcTemplate;

    public CampaignService(JdbcTemplate jdbcTemplate) { this.jdbcTemplate = jdbcTemplate; }

    @Transactional(readOnly = true)
    public Optional<CampaignView> activeCampaign(String languageCode) {
        List<CampaignView> results = jdbcTemplate.query("""
                SELECT c.id,c.campaign_code,COALESCE(t.title,en.title),COALESCE(t.description,en.description),
                       c.ticket_price,c.currency,c.sales_start_at,c.sales_close_at,c.draw_at,c.status,c.promotional_image_file_id
                FROM campaigns c
                JOIN campaign_translations en ON en.campaign_id=c.id AND en.language_code='en'
                LEFT JOIN campaign_translations t ON t.campaign_id=c.id AND t.language_code=?
                WHERE c.status IN ('OPEN','SCHEDULED') AND (c.sales_close_at IS NULL OR c.sales_close_at>UTC_TIMESTAMP(6))
                ORDER BY CASE c.status WHEN 'OPEN' THEN 0 ELSE 1 END,c.sales_start_at,c.id
                LIMIT 1
                """, (rs, row) -> new CampaignView(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getBigDecimal(5), rs.getString(6), instant(rs.getTimestamp(7)), instant(rs.getTimestamp(8)),
                instant(rs.getTimestamp(9)), CampaignStatus.valueOf(rs.getString(10)), rs.getString(11)), languageCode);
        return results.stream().findFirst();
    }

    @Transactional(readOnly = true)
    public Optional<CampaignView> openCampaign(String languageCode) {
        List<CampaignView> results = jdbcTemplate.query("""
                SELECT c.id,c.campaign_code,COALESCE(t.title,en.title),COALESCE(t.description,en.description),
                       c.ticket_price,c.currency,c.sales_start_at,c.sales_close_at,c.draw_at,c.status,c.promotional_image_file_id
                FROM campaigns c
                JOIN campaign_translations en ON en.campaign_id=c.id AND en.language_code='en'
                LEFT JOIN campaign_translations t ON t.campaign_id=c.id AND t.language_code=?
                WHERE c.status='OPEN' AND (c.sales_start_at IS NULL OR c.sales_start_at<=UTC_TIMESTAMP(6))
                  AND (c.sales_close_at IS NULL OR c.sales_close_at>UTC_TIMESTAMP(6))
                ORDER BY c.sales_start_at,c.id LIMIT 1
                """, (rs,row)->new CampaignView(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getBigDecimal(5),
                rs.getString(6),instant(rs.getTimestamp(7)),instant(rs.getTimestamp(8)),instant(rs.getTimestamp(9)),CampaignStatus.valueOf(rs.getString(10)),rs.getString(11)),languageCode);
        return results.stream().findFirst();
    }

    @Transactional(readOnly = true)
    public List<PrizeView> prizes(long campaignId, String languageCode) {
        return jdbcTemplate.query("""
                SELECT p.id,COALESCE(t.name,en.name),COALESCE(t.description,en.description),p.category,p.quantity,p.monetary_value,p.image_file_id
                FROM prizes p
                JOIN prize_translations en ON en.prize_id=p.id AND en.language_code='en'
                LEFT JOIN prize_translations t ON t.prize_id=p.id AND t.language_code=?
                WHERE p.campaign_id=? AND p.active=TRUE
                ORDER BY p.display_order,p.id
                """, (rs, row) -> new PrizeView(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getInt(5), rs.getBigDecimal(6), rs.getString(7)), languageCode, campaignId);
    }

    private static java.time.Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
}
