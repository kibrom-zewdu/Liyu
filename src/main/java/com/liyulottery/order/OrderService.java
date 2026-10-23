package com.liyulottery.order;

import com.liyulottery.admin.AdminAuthorizationService;
import com.liyulottery.admin.AdminRole;
import com.liyulottery.ticket.TicketIssuanceService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
public class OrderService {
    private final JdbcTemplate jdbc;
    private final AdminAuthorizationService authorization;
    private final boolean paymentsEnabled;
    private final long maximumReceiptBytes;
    private final TicketIssuanceService ticketIssuance;

    public OrderService(JdbcTemplate jdbc, AdminAuthorizationService authorization,
                        @Value("${app.payments-enabled:false}") boolean paymentsEnabled,
                        @Value("${app.receipt.max-size-bytes:10485760}") long maximumReceiptBytes,
                        TicketIssuanceService ticketIssuance) {
        this.jdbc=jdbc; this.authorization=authorization; this.paymentsEnabled=paymentsEnabled; this.maximumReceiptBytes=maximumReceiptBytes; this.ticketIssuance=ticketIssuance;
    }

    @Transactional
    public OrderQuote quote(long telegramUserId,long campaignId,int quantity) {
        if (!paymentsEnabled) throw new IllegalStateException("Ticket purchasing is disabled until business approval is configured.");
        if (quantity<1 || quantity>20) throw new IllegalArgumentException("Quantity must be between 1 and 20.");
        return jdbc.query("SELECT c.ticket_price,c.currency,c.per_user_limit,c.ticket_limit,COALESCE((SELECT SUM(o.quantity) FROM orders o WHERE o.campaign_id=c.id AND o.status IN ('PENDING_PAYMENT','RECEIPT_SUBMITTED','UNDER_REVIEW','PAYMENT_VERIFIED','APPROVED')),0),COALESCE((SELECT SUM(o.quantity) FROM orders o WHERE o.campaign_id=c.id AND o.user_id=u.id AND o.status IN ('PENDING_PAYMENT','RECEIPT_SUBMITTED','UNDER_REVIEW','PAYMENT_VERIFIED','APPROVED')),0),u.id FROM campaigns c JOIN users u ON u.telegram_user_id=? WHERE c.id=? AND c.status='OPEN' AND (c.sales_start_at IS NULL OR c.sales_start_at<=UTC_TIMESTAMP(6)) AND (c.sales_close_at IS NULL OR c.sales_close_at>UTC_TIMESTAMP(6)) FOR UPDATE",rs->{
            if(!rs.next()) throw new IllegalStateException("That campaign is not open for ticket sales.");
            BigDecimal unit=rs.getBigDecimal(1); String currency=rs.getString(2);
            int perUser=rs.getInt(3); Integer userLimit=rs.wasNull()?null:perUser;
            long limit=rs.getLong(4); Long inventory=rs.wasNull()?null:limit;
            if(userLimit!=null && rs.getLong(6)+quantity>userLimit) throw new IllegalArgumentException("Quantity exceeds the per-customer limit.");
            if(inventory!=null && rs.getLong(5)+quantity>inventory) throw new IllegalArgumentException("Not enough tickets remain in this campaign.");
            BigDecimal total=unit.multiply(BigDecimal.valueOf(quantity));
            jdbc.update("INSERT INTO order_drafts(user_id,campaign_id,quantity,quoted_unit_price,quoted_total,currency,expires_at) VALUES(?,?,?,?,?,?,UTC_TIMESTAMP(6)+INTERVAL 15 MINUTE) ON DUPLICATE KEY UPDATE campaign_id=VALUES(campaign_id),quantity=VALUES(quantity),quoted_unit_price=VALUES(quoted_unit_price),quoted_total=VALUES(quoted_total),currency=VALUES(currency),expires_at=VALUES(expires_at)",rs.getLong(7),campaignId,quantity,unit,total,currency);
            return new OrderQuote(quantity,unit,total,currency);
        },telegramUserId,campaignId);
    }

    @Transactional
    public OrderSummary confirm(long telegramUserId) {
        if(!paymentsEnabled) throw new IllegalStateException("Ticket purchasing is disabled.");
        Integer configuredMethods=jdbc.queryForObject("SELECT COUNT(*) FROM payment_instructions WHERE active=TRUE",Integer.class);
        if(configuredMethods==null||configuredMethods==0) throw new IllegalStateException("Payment instructions have not been configured by an authorized administrator.");
        return jdbc.query("SELECT d.campaign_id,d.quantity,d.quoted_unit_price,d.quoted_total,d.currency,u.id,c.ticket_price,c.status,c.ticket_limit,c.per_user_limit,COALESCE((SELECT SUM(o.quantity) FROM orders o WHERE o.campaign_id=c.id AND o.status IN ('PENDING_PAYMENT','RECEIPT_SUBMITTED','UNDER_REVIEW','PAYMENT_VERIFIED','APPROVED')),0),COALESCE((SELECT SUM(o.quantity) FROM orders o WHERE o.campaign_id=c.id AND o.user_id=u.id AND o.status IN ('PENDING_PAYMENT','RECEIPT_SUBMITTED','UNDER_REVIEW','PAYMENT_VERIFIED','APPROVED')),0),c.sales_start_at,c.sales_close_at FROM order_drafts d JOIN users u ON u.id=d.user_id JOIN campaigns c ON c.id=d.campaign_id WHERE u.telegram_user_id=? AND d.expires_at>UTC_TIMESTAMP(6) FOR UPDATE",rs->{
            if(!rs.next()) throw new IllegalStateException("Your order quote expired. Start again with /buy.");
            long campaignId=rs.getLong(1),userId=rs.getLong(6); int quantity=rs.getInt(2); BigDecimal unit=rs.getBigDecimal(3),total=rs.getBigDecimal(4); String currency=rs.getString(5);
            java.sql.Timestamp starts=rs.getTimestamp(13), closes=rs.getTimestamp(14);
            if(!"OPEN".equals(rs.getString(8)) || unit.compareTo(rs.getBigDecimal(7))!=0 || (starts!=null&&starts.toInstant().isAfter(java.time.Instant.now())) || (closes!=null&&!closes.toInstant().isAfter(java.time.Instant.now()))) throw new IllegalStateException("Campaign availability or price changed. Please request a new quote.");
            long ticketLimit=rs.getLong(9); boolean limitedTickets=!rs.wasNull();
            int perUserLimit=rs.getInt(10); boolean limitedUser=!rs.wasNull();
            if(limitedTickets&&rs.getLong(11)+quantity>ticketLimit) throw new IllegalStateException("Not enough tickets remain. Please request a new quote.");
            if(limitedUser&&rs.getLong(12)+quantity>perUserLimit) throw new IllegalStateException("Your purchase would exceed this campaign's limit.");
            String reference="LL-"+UUID.randomUUID().toString().replace("-","").substring(0,16).toUpperCase();
            jdbc.update("INSERT INTO orders(order_reference,user_id,campaign_id,quantity,unit_price,total_amount,currency,status) VALUES(?,?,?,?,?,?,?,'PENDING_PAYMENT')",reference,userId,campaignId,quantity,unit,total,currency);
            jdbc.update("DELETE FROM order_drafts WHERE user_id=?",userId);
            return new OrderSummary(reference,quantity,unit,total,currency,"PENDING_PAYMENT");
        },telegramUserId);
    }

    @Transactional(readOnly=true)
    public List<PaymentInstruction> paymentInstructions(String language) {
        return jdbc.query("SELECT i.method_code,t.title,t.instructions FROM payment_instructions i JOIN payment_instruction_translations t ON t.payment_instruction_id=i.id AND t.language_code=? WHERE i.active=TRUE ORDER BY i.id",(rs,n)->new PaymentInstruction(rs.getString(1),rs.getString(2),rs.getString(3)),language);
    }

    @Transactional
    public int submitReceipt(long telegramUserId,String reference,String fileId,String mimeType,long fileSize) {
        if(!paymentsEnabled) throw new IllegalStateException("Ticket purchasing is disabled.");
        if(fileId==null||fileId.isBlank()||fileSize<1||fileSize>maximumReceiptBytes||!("image/jpeg".equals(mimeType)||"image/png".equals(mimeType)||"image/webp".equals(mimeType)||"application/pdf".equals(mimeType))) throw new IllegalArgumentException("Receipt must be a JPEG, PNG, WebP image, or PDF within the configured size limit.");
        return jdbc.query("SELECT o.id,o.user_id,o.status FROM orders o JOIN users u ON u.id=o.user_id WHERE o.order_reference=? AND u.telegram_user_id=? FOR UPDATE",rs->{
            if(!rs.next()) throw new IllegalArgumentException("Order not found for your account.");
            long orderId=rs.getLong(1),userId=rs.getLong(2); String status=rs.getString(3);
            if(!("PENDING_PAYMENT".equals(status)||"REJECTED".equals(status))) throw new IllegalStateException("This order cannot accept another receipt.");
            Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM payment_receipts WHERE order_id=?",Integer.class,orderId);
            if(count!=null&&count>=3) throw new IllegalStateException("Receipt submission limit reached for this order.");
            int number=(count==null?0:count)+1;
            jdbc.update("INSERT INTO payment_receipts(order_id,user_id,telegram_file_id,mime_type,file_size,submission_number,status) VALUES(?,?,?,?,?,?,'PENDING')",orderId,userId,fileId,mimeType,fileSize,number);
            jdbc.update("UPDATE orders SET status='RECEIPT_SUBMITTED' WHERE id=?",orderId); return number;
        },reference,telegramUserId);
    }

    @Transactional(readOnly=true)
    public List<PendingPayment> pendingPayments(long telegramAdminId) {
        authorization.requireRole(telegramAdminId,AdminRole.PAYMENT_REVIEWER);
        return jdbc.query("SELECT o.order_reference,o.total_amount,o.currency,o.quantity,r.id,r.telegram_file_id,r.mime_type,r.submitted_at,u.telegram_user_id,u.display_name FROM orders o JOIN payment_receipts r ON r.order_id=o.id AND r.status='PENDING' JOIN users u ON u.id=o.user_id WHERE o.status='RECEIPT_SUBMITTED' ORDER BY r.submitted_at LIMIT 50",(rs,n)->new PendingPayment(rs.getString(1),rs.getBigDecimal(2),rs.getString(3),rs.getInt(4),rs.getLong(5),rs.getString(6),rs.getString(7),rs.getTimestamp(8).toInstant(),rs.getLong(9),rs.getString(10)));
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public List<String> decide(long telegramAdminId,String reference,boolean approve,String transactionReference,String note) {
        long adminId=authorization.requireRole(telegramAdminId,AdminRole.PAYMENT_REVIEWER);
        return jdbc.query("SELECT o.id,o.status,r.id FROM orders o JOIN payment_receipts r ON r.order_id=o.id AND r.status='PENDING' WHERE o.order_reference=? ORDER BY r.submission_number DESC LIMIT 1 FOR UPDATE",rs->{
            if(!rs.next()) throw new IllegalStateException("No pending receipt found for that order.");
            long orderId=rs.getLong(1),receiptId=rs.getLong(3);
            if(!"RECEIPT_SUBMITTED".equals(rs.getString(2))) throw new IllegalStateException("Order is not awaiting payment review.");
            if(approve&&(transactionReference==null||transactionReference.isBlank())) throw new IllegalArgumentException("A verified transaction reference is required for approval.");
            jdbc.update("INSERT INTO payment_verifications(order_id,receipt_id,reviewer_admin_id,decision,transaction_reference,internal_note) VALUES(?,?,?,?,?,?)",orderId,receiptId,adminId,approve?"APPROVED":"REJECTED",transactionReference,note);
            jdbc.update("UPDATE payment_receipts SET status=? WHERE id=?",approve?"APPROVED":"REJECTED",receiptId);
            jdbc.update("UPDATE orders SET status=? WHERE id=?",approve?"PAYMENT_VERIFIED":"REJECTED",orderId);
            jdbc.update("INSERT INTO audit_logs(actor_admin_id,action,target_type,target_id,after_json) VALUES(?,?,?,?,JSON_OBJECT('decision',?))",adminId,approve?"PAYMENT_APPROVED":"PAYMENT_REJECTED","ORDER",reference,approve?"APPROVED":"REJECTED");
            return approve?ticketIssuance.issueVerifiedOrder(telegramAdminId,reference):List.of();
        },reference);
    }

    @Transactional(readOnly=true)
    public OrderCustomer orderCustomer(String reference) {
        return jdbc.queryForObject("SELECT u.telegram_user_id,COALESCE(u.language_code,'en') FROM orders o JOIN users u ON u.id=o.user_id WHERE o.order_reference=?",(rs,n)->new OrderCustomer(rs.getLong(1),rs.getString(2)),reference);
    }

    public record OrderQuote(int quantity,BigDecimal unitPrice,BigDecimal total,String currency) {}
    public record OrderSummary(String reference,int quantity,BigDecimal unitPrice,BigDecimal total,String currency,String status) {}
    public record OrderCustomer(long telegramUserId,String language) {}
    public record PaymentInstruction(String code,String title,String instructions) {}
    public record PendingPayment(String reference,BigDecimal total,String currency,int quantity,long receiptId,String fileId,String mimeType,java.time.Instant submittedAt,long customerTelegramId,String customerName) {}
}
