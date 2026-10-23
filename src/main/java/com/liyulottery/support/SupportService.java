package com.liyulottery.support;

import com.liyulottery.admin.AdminAuthorizationService;
import com.liyulottery.admin.AdminRole;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class SupportService {
    private final JdbcTemplate jdbc;
    private final AdminAuthorizationService authorization;
    public SupportService(JdbcTemplate jdbc,AdminAuthorizationService authorization){this.jdbc=jdbc;this.authorization=authorization;}

    @Transactional
    public long open(long telegramUserId,String message) {
        validate(message);
        Long userId=jdbc.queryForObject("SELECT id FROM users WHERE telegram_user_id=?",Long.class,telegramUserId);
        jdbc.update("INSERT INTO support_requests(user_id,status,subject) VALUES(?,'OPEN',?)",userId,message.trim().substring(0,Math.min(200,message.trim().length())));
        Long requestId=jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
        jdbc.update("INSERT INTO support_messages(support_request_id,sender_user_id,message_text) VALUES(?,?,?)",requestId,userId,message.trim());
        return requestId;
    }

    @Transactional
    public void customerMessage(long telegramUserId,long requestId,String message) {
        validate(message);
        jdbc.query("SELECT sr.id,sr.status,u.id FROM support_requests sr JOIN users u ON u.id=sr.user_id WHERE sr.id=? AND u.telegram_user_id=? FOR UPDATE",rs->{
            if(!rs.next()) throw new IllegalArgumentException("Support request not found for your account.");
            if(!"OPEN".equals(rs.getString(2))) throw new IllegalStateException("This support request is closed. Open a new request with /support.");
            jdbc.update("INSERT INTO support_messages(support_request_id,sender_user_id,message_text) VALUES(?,?,?)",requestId,rs.getLong(3),message.trim());
            jdbc.update("UPDATE support_requests SET updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",requestId);
            return null;
        },requestId,telegramUserId);
    }

    @Transactional(readOnly=true)
    public List<OpenRequest> openRequests(long agentTelegramId) {
        authorization.requireRole(agentTelegramId,AdminRole.SUPPORT_AGENT);
        return jdbc.query("SELECT sr.id,u.telegram_user_id,u.display_name,sr.subject,sr.created_at,sr.updated_at FROM support_requests sr JOIN users u ON u.id=sr.user_id WHERE sr.status='OPEN' ORDER BY sr.updated_at DESC LIMIT 30",(rs,n)->new OpenRequest(rs.getLong(1),rs.getLong(2),rs.getString(3),rs.getString(4),rs.getTimestamp(5).toInstant(),rs.getTimestamp(6).toInstant()));
    }

    @Transactional
    public CustomerTarget reply(long agentTelegramId,long requestId,String message) {
        validate(message); long adminId=authorization.requireRole(agentTelegramId,AdminRole.SUPPORT_AGENT);
        return jdbc.query("SELECT sr.status,u.telegram_user_id,COALESCE(u.language_code,'en') FROM support_requests sr JOIN users u ON u.id=sr.user_id WHERE sr.id=? FOR UPDATE",rs->{
            if(!rs.next()) throw new IllegalArgumentException("Support request not found.");
            if(!"OPEN".equals(rs.getString(1))) throw new IllegalStateException("Support request is closed.");
            long customerId=rs.getLong(2); String language=rs.getString(3);
            jdbc.update("INSERT INTO support_messages(support_request_id,sender_admin_id,message_text) VALUES(?,?,?)",requestId,adminId,message.trim());
            jdbc.update("UPDATE support_requests SET updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",requestId);
            jdbc.update("INSERT INTO audit_logs(actor_admin_id,action,target_type,target_id,after_json) VALUES(?,?,?,?,JSON_OBJECT('message_length',?))",adminId,"SUPPORT_REPLY_SENT","SUPPORT_REQUEST",requestId,message.length());
            return new CustomerTarget(customerId,language);
        },requestId);
    }

    @Transactional
    public void close(long agentTelegramId,long requestId) {
        long adminId=authorization.requireRole(agentTelegramId,AdminRole.SUPPORT_AGENT);
        int changed=jdbc.update("UPDATE support_requests SET status='CLOSED',updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND status='OPEN'",requestId);
        if(changed!=1) throw new IllegalStateException("Open support request not found.");
        jdbc.update("INSERT INTO audit_logs(actor_admin_id,action,target_type,target_id,after_json) VALUES(?,?,?,?,JSON_OBJECT('status','CLOSED'))",adminId,"SUPPORT_REQUEST_CLOSED","SUPPORT_REQUEST",requestId);
    }

    private static void validate(String message){if(message==null||message.isBlank()||message.length()>4000)throw new IllegalArgumentException("Enter a message of 1–4000 characters.");}
    public record OpenRequest(long id,long telegramUserId,String displayName,String subject,java.time.Instant createdAt,java.time.Instant updatedAt){}
    public record CustomerTarget(long telegramUserId,String language){}
}
