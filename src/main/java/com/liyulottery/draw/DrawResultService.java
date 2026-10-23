package com.liyulottery.draw;

import com.liyulottery.admin.AdminAuthorizationService;
import com.liyulottery.admin.AdminRole;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class DrawResultService {
    private final JdbcTemplate jdbc;
    private final AdminAuthorizationService authorization;
    public DrawResultService(JdbcTemplate jdbc,AdminAuthorizationService authorization){this.jdbc=jdbc;this.authorization=authorization;}

    @Transactional
    public long prepare(long actorTelegramId,long campaignId,java.util.Map<String,String> notices,String winners) {
        long actor=authorization.requireRole(actorTelegramId,AdminRole.RESULTS_MANAGER);
        if(notices==null||!notices.keySet().containsAll(Set.of("en","am","ti"))||notices.values().stream().anyMatch(n->n==null||n.isBlank()||n.length()>4000)) throw new IllegalArgumentException("Enter reviewed official notices of 1–4000 characters in English, Amharic, and Tigrigna.");
        if(winners==null||winners.isBlank()) throw new IllegalArgumentException("Add at least one ticket number, optionally followed by :PRIZE_ID.");
        Campaign campaign=jdbc.queryForObject("SELECT status,draw_at FROM campaigns WHERE id=? FOR UPDATE",(rs,n)->new Campaign(rs.getString(1),rs.getTimestamp(2)==null?null:rs.getTimestamp(2).toInstant()),campaignId);
        if(campaign==null||!("CLOSED".equals(campaign.status)||"DRAW_PENDING".equals(campaign.status))) throw new IllegalStateException("Results can only be prepared for a closed campaign awaiting its draw.");
        if(campaign.drawAt==null||campaign.drawAt.isAfter(Instant.now())) throw new IllegalStateException("The scheduled draw time has not arrived.");
        Integer next=jdbc.queryForObject("SELECT COALESCE(MAX(version_number),0)+1 FROM draw_results WHERE campaign_id=?",Integer.class,campaignId);
        jdbc.update("INSERT INTO draw_results(campaign_id,version_number,status,notice,prepared_by_admin_id) VALUES(?,?,'DRAFT',?,?)",campaignId,next,notices.get("en").trim(),actor);
        long resultId=jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
        notices.forEach((language,notice)->jdbc.update("INSERT INTO draw_result_translations(draw_result_id,language_code,notice) VALUES(?,?,?)",resultId,language,notice.trim()));
        Set<String> seen=new HashSet<>();
        for(String token:winners.split(",")) {
            String[] parts=token.trim().split(":",-1);
            if(parts.length<1||parts.length>2||parts[0].isBlank()||!seen.add(parts[0])) throw new IllegalArgumentException("Each winning ticket must be listed once as TICKET_NUMBER or TICKET_NUMBER:PRIZE_ID.");
            Long ticketId=jdbc.query("SELECT id FROM tickets WHERE campaign_id=? AND ticket_number=? AND status IN ('ACTIVE','WINNER')",rs->rs.next()?rs.getLong(1):null,campaignId,parts[0].trim());
            if(ticketId==null) throw new IllegalArgumentException("Ticket not found in this campaign: "+parts[0]);
            Long prizeId=null;
            if(parts.length==2&&!parts[1].isBlank()) {
                prizeId=Long.parseLong(parts[1]);
                Integer belongs=jdbc.queryForObject("SELECT COUNT(*) FROM prizes WHERE id=? AND campaign_id=?",Integer.class,prizeId,campaignId);
                if(belongs==null||belongs!=1) throw new IllegalArgumentException("Prize does not belong to this campaign: "+parts[1]);
            }
            jdbc.update("INSERT INTO draw_result_entries(draw_result_id,ticket_id,prize_id) VALUES(?,?,?)",resultId,ticketId,prizeId);
        }
        jdbc.update("INSERT INTO audit_logs(actor_admin_id,action,target_type,target_id,after_json) VALUES(?,?,?,?,JSON_OBJECT('version',?,'winner_count',?))",actor,"DRAW_RESULT_PREPARED","DRAW_RESULT",resultId,next,seen.size());
        return resultId;
    }

    @Transactional
    public void publish(long actorTelegramId,long resultId) {
        long actor=authorization.requireRole(actorTelegramId,AdminRole.RESULTS_MANAGER);
        DrawResult result=jdbc.queryForObject("SELECT campaign_id,version_number,status FROM draw_results WHERE id=? FOR UPDATE",(rs,n)->new DrawResult(rs.getLong(1),rs.getInt(2),rs.getString(3)),resultId);
        if(result==null||!"DRAFT".equals(result.status)) throw new IllegalStateException("Only a draft result can be published.");
        Campaign campaign=jdbc.queryForObject("SELECT status,draw_at FROM campaigns WHERE id=? FOR UPDATE",(rs,n)->new Campaign(rs.getString(1),rs.getTimestamp(2)==null?null:rs.getTimestamp(2).toInstant()),result.campaignId);
        if(campaign==null||!("CLOSED".equals(campaign.status)||"DRAW_PENDING".equals(campaign.status))||campaign.drawAt==null||campaign.drawAt.isAfter(Instant.now())) throw new IllegalStateException("Campaign is not ready for official result publication.");
        jdbc.update("UPDATE draw_results SET status='SUPERSEDED' WHERE campaign_id=? AND status='PUBLISHED'",result.campaignId);
        jdbc.update("UPDATE draw_results SET status='PUBLISHED',published_by_admin_id=?,published_at=UTC_TIMESTAMP(6) WHERE id=? AND status='DRAFT'",actor,resultId);
        jdbc.update("UPDATE tickets SET status='ACTIVE' WHERE campaign_id=? AND status='WINNER'",result.campaignId);
        jdbc.update("UPDATE tickets t JOIN draw_result_entries e ON e.ticket_id=t.id SET t.status='WINNER' WHERE e.draw_result_id=?",resultId);
        jdbc.update("UPDATE campaigns SET status='COMPLETED',updated_by_admin_id=? WHERE id=? AND status IN ('CLOSED','DRAW_PENDING')",actor,result.campaignId);
        jdbc.update("INSERT IGNORE INTO notification_records(event_key,user_id,message_key,payload_json) SELECT CONCAT('DRAW_RESULT_',?),t.user_id,'results.notification',JSON_OBJECT('result_id',?) FROM tickets t WHERE t.campaign_id=? GROUP BY t.user_id",resultId,resultId,result.campaignId);
        jdbc.update("INSERT INTO audit_logs(actor_admin_id,action,target_type,target_id,after_json) VALUES(?,?,?,?,JSON_OBJECT('version',?,'published',TRUE))",actor,"DRAW_RESULT_PUBLISHED","DRAW_RESULT",resultId,result.version);
    }

    @Transactional(readOnly=true)
    public List<PublishedResult> published(String language) {
        return jdbc.query("SELECT r.id,r.campaign_id,COALESCE(ct.title,ce.title),r.version_number,COALESCE(rt.notice,re.notice),r.published_at,t.ticket_number,COALESCE(pt.name,pe.name),p.category FROM draw_results r JOIN campaigns c ON c.id=r.campaign_id JOIN campaign_translations ce ON ce.campaign_id=c.id AND ce.language_code='en' LEFT JOIN campaign_translations ct ON ct.campaign_id=c.id AND ct.language_code=? JOIN draw_result_translations re ON re.draw_result_id=r.id AND re.language_code='en' LEFT JOIN draw_result_translations rt ON rt.draw_result_id=r.id AND rt.language_code=? JOIN draw_result_entries e ON e.draw_result_id=r.id JOIN tickets t ON t.id=e.ticket_id LEFT JOIN prizes p ON p.id=e.prize_id LEFT JOIN prize_translations pe ON pe.prize_id=p.id AND pe.language_code='en' LEFT JOIN prize_translations pt ON pt.prize_id=p.id AND pt.language_code=? WHERE r.status='PUBLISHED' AND r.version_number=(SELECT MAX(latest.version_number) FROM draw_results latest WHERE latest.campaign_id=r.campaign_id AND latest.status IN ('PUBLISHED','SUPERSEDED')) ORDER BY r.published_at DESC,e.id",(rs,n)->new PublishedResult(rs.getLong(1),rs.getLong(2),rs.getString(3),rs.getInt(4),rs.getString(5),rs.getTimestamp(6).toInstant(),rs.getString(7),rs.getString(8),rs.getString(9)),language,language,language);
    }

    public record PublishedResult(long resultId,long campaignId,String campaignTitle,int version,String notice,Instant publishedAt,String ticketNumber,String prizeName,String prizeCategory){}
    private record Campaign(String status,Instant drawAt){}
    private record DrawResult(long campaignId,int version,String status){}
}
