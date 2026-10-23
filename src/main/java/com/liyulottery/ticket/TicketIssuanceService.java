package com.liyulottery.ticket;

import com.liyulottery.admin.AdminAuthorizationService;
import com.liyulottery.admin.AdminRole;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
public class TicketIssuanceService {
    private final JdbcTemplate jdbc;
    private final AdminAuthorizationService authorization;
    public TicketIssuanceService(JdbcTemplate jdbc,AdminAuthorizationService authorization){this.jdbc=jdbc;this.authorization=authorization;}

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public List<String> issueVerifiedOrder(long reviewerTelegramId,String reference) {
        authorization.requireRole(reviewerTelegramId,AdminRole.PAYMENT_REVIEWER);
        return jdbc.query("SELECT o.id,o.campaign_id,o.user_id,o.quantity,o.status FROM orders o WHERE o.order_reference=? FOR UPDATE",rs->{
            if(!rs.next()) throw new IllegalArgumentException("Order not found.");
            long orderId=rs.getLong(1),campaignId=rs.getLong(2),userId=rs.getLong(3); int quantity=rs.getInt(4); String status=rs.getString(5);
            if("APPROVED".equals(status)) return jdbc.query("SELECT ticket_number FROM tickets WHERE order_id=? ORDER BY id",(tickets,n)->tickets.getString(1),orderId);
            if(!"PAYMENT_VERIFIED".equals(status)) throw new IllegalStateException("Tickets can only be issued after a payment reviewer verifies payment.");
            jdbc.update("INSERT INTO campaign_ticket_counters(campaign_id,last_number) VALUES(?,LAST_INSERT_ID(?)) ON DUPLICATE KEY UPDATE last_number=LAST_INSERT_ID(last_number+VALUES(last_number))",campaignId,quantity);
            long sequence=jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class)-quantity;
            List<String> numbers=new ArrayList<>(quantity);
            for(int i=1;i<=quantity;i++) {
                long next=sequence+i;
                if(next<1) throw new IllegalStateException("Ticket number sequence exhausted.");
                String number=String.format(java.util.Locale.ROOT,"%010d",next);
                jdbc.update("INSERT INTO tickets(campaign_id,order_id,user_id,ticket_number,status) VALUES(?,?,?,?,'ACTIVE')",campaignId,orderId,userId,number);
                numbers.add(number);
            }
            jdbc.update("UPDATE campaign_ticket_counters SET last_number=? WHERE campaign_id=?",sequence+quantity,campaignId);
            jdbc.update("UPDATE orders SET status='APPROVED' WHERE id=? AND status='PAYMENT_VERIFIED'",orderId);
            jdbc.update("INSERT INTO audit_logs(actor_admin_id,action,target_type,target_id,after_json) VALUES((SELECT id FROM admins WHERE telegram_user_id=?),'TICKETS_ISSUED','ORDER',?,JSON_OBJECT('quantity',?))",reviewerTelegramId,reference,quantity);
            return numbers;
        },reference);
    }

    @Transactional(readOnly=true)
    public List<CustomerTicket> customerTickets(long telegramUserId,int pageSize,int page) {
        if(pageSize<1||pageSize>50||page<0) throw new IllegalArgumentException("Invalid ticket page.");
        long offset=(long)pageSize*page;
        return jdbc.query("SELECT t.ticket_number,t.status,o.order_reference,COALESCE(ct.title,ce.title),c.draw_at,t.issued_at FROM tickets t JOIN users u ON u.id=t.user_id JOIN orders o ON o.id=t.order_id JOIN campaigns c ON c.id=t.campaign_id JOIN campaign_translations ce ON ce.campaign_id=c.id AND ce.language_code='en' LEFT JOIN campaign_translations ct ON ct.campaign_id=c.id AND ct.language_code=u.language_code WHERE u.telegram_user_id=? ORDER BY t.issued_at DESC,t.id DESC LIMIT ? OFFSET ?",(rs,n)->new CustomerTicket(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getTimestamp(5)==null?null:rs.getTimestamp(5).toInstant(),rs.getTimestamp(6).toInstant()),telegramUserId,pageSize,offset);
    }
    public record CustomerTicket(String number,String status,String orderReference,String campaignTitle,java.time.Instant drawAt,java.time.Instant issuedAt){}
}
