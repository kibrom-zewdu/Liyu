package com.liyulottery.notification;

import com.liyulottery.common.TranslationService;
import com.liyulottery.telegram.TelegramApiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(prefix="app.telegram",name="enabled",havingValue="true")
public class ResultNotificationWorker {
    private static final Logger log=LoggerFactory.getLogger(ResultNotificationWorker.class);
    private final JdbcTemplate jdbc;
    private final TelegramApiClient telegram;
    private final TranslationService translations;
    public ResultNotificationWorker(JdbcTemplate jdbc,TelegramApiClient telegram,TranslationService translations){this.jdbc=jdbc;this.telegram=telegram;this.translations=translations;}

    @Scheduled(fixedDelay=5000)
    public void deliver(){
        List<Long> pending=jdbc.query("SELECT id FROM notification_records WHERE status='PENDING' AND message_key='results.notification' AND available_at<=UTC_TIMESTAMP(6) ORDER BY id LIMIT 100",(rs,n)->rs.getLong(1));
        for(long id:pending){
            try {
                Notice notice=jdbc.queryForObject("SELECT n.id,u.telegram_user_id,COALESCE(u.language_code,'en'),COALESCE(ct.title,ce.title),COALESCE(rt.notice,re.notice) FROM notification_records n JOIN users u ON u.id=n.user_id JOIN draw_results r ON r.id=CAST(SUBSTRING(n.event_key,13) AS UNSIGNED) AND r.status='PUBLISHED' JOIN campaigns c ON c.id=r.campaign_id JOIN campaign_translations ce ON ce.campaign_id=c.id AND ce.language_code='en' LEFT JOIN campaign_translations ct ON ct.campaign_id=c.id AND ct.language_code=u.language_code JOIN draw_result_translations re ON re.draw_result_id=r.id AND re.language_code='en' LEFT JOIN draw_result_translations rt ON rt.draw_result_id=r.id AND rt.language_code=u.language_code WHERE n.id=? AND n.status='PENDING'",(rs,n)->new Notice(rs.getLong(1),rs.getLong(2),rs.getString(3),rs.getString(4),rs.getString(5)),id);
                telegram.sendMessage(notice.telegramUserId,translations.text("results.notification",notice.language,notice.campaignTitle,notice.notice),Map.of("remove_keyboard",true));
                jdbc.update("UPDATE notification_records SET status='SENT',attempts=attempts+1,sent_at=UTC_TIMESTAMP(6) WHERE id=? AND status='PENDING'",id);
            } catch(Exception failure){
                jdbc.update("UPDATE notification_records SET attempts=attempts+1,available_at=UTC_TIMESTAMP(6)+INTERVAL LEAST(POW(2,attempts),300) SECOND,status=IF(attempts>=9,'FAILED','PENDING') WHERE id=? AND status='PENDING'",id);
                log.warn("Official result notification could not be delivered");
            }
        }
    }
    private record Notice(long id,long telegramUserId,String language,String campaignTitle,String notice){}
}
