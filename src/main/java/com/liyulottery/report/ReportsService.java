package com.liyulottery.report;

import com.liyulottery.admin.AdminAuthorizationService;
import com.liyulottery.admin.AdminRole;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

@Service
public class ReportsService {
    private final JdbcTemplate jdbc;
    private final AdminAuthorizationService authorization;
    public ReportsService(JdbcTemplate jdbc,AdminAuthorizationService authorization){this.jdbc=jdbc;this.authorization=authorization;}

    @Transactional(readOnly=true)
    public String summary(long adminTelegramId) {
        authorization.requireRole(adminTelegramId,AdminRole.SUPER_ADMIN);
        long registered=scalar("SELECT COUNT(*) FROM users");
        StringBuilder report=new StringBuilder("LIYU LOTTERY administrative report\n\nRegistered customers: ").append(registered);
        List<CountRow> languages=jdbc.query("SELECT COALESCE(language_code,'unset'),COUNT(*) FROM users GROUP BY language_code ORDER BY language_code",(rs,n)->new CountRow(rs.getString(1),rs.getLong(2)));
        report.append("\nLanguages: "); appendCounts(report,languages);
        report.append("\n\nCampaigns by status:");
        appendRows(report,jdbc.query("SELECT status,COUNT(*) FROM campaigns GROUP BY status ORDER BY status",(rs,n)->new CountRow(rs.getString(1),rs.getLong(2))));
        report.append("\n\nOrders by status (claimed amount; not verified revenue):");
        List<AmountRow> orderStatuses=jdbc.query("SELECT status,currency,COUNT(*),COALESCE(SUM(total_amount),0) FROM orders GROUP BY status,currency ORDER BY status,currency",(rs,n)->new AmountRow(rs.getString(1),rs.getString(2),rs.getLong(3),rs.getBigDecimal(4)));
        for(AmountRow row:orderStatuses) report.append("\n").append(row.name).append(": ").append(row.count).append(" — ").append(row.amount.toPlainString()).append(" ").append(row.currency);
        report.append("\n\nVerified payments:");
        List<AmountRow> verified=jdbc.query("SELECT currency,COUNT(*),COALESCE(SUM(total_amount),0) FROM orders WHERE status IN ('PAYMENT_VERIFIED','APPROVED') GROUP BY currency ORDER BY currency",(rs,n)->new AmountRow("verified orders",rs.getString(1),rs.getLong(2),rs.getBigDecimal(3)));
        if(verified.isEmpty()) report.append(" none");
        for(AmountRow row:verified) report.append("\n").append(row.count).append(" orders — ").append(row.amount.toPlainString()).append(" ").append(row.currency);
        report.append("\n\nReceipts awaiting review: ").append(scalar("SELECT COUNT(*) FROM payment_receipts WHERE status='PENDING'"));
        report.append("\nTickets issued:");
        List<CountRow> ticketRows=jdbc.query("SELECT c.campaign_code,COUNT(t.id) FROM campaigns c LEFT JOIN tickets t ON t.campaign_id=c.id GROUP BY c.id,c.campaign_code ORDER BY c.id DESC LIMIT 20",(rs,n)->new CountRow(rs.getString(1),rs.getLong(2)));
        appendRows(report,ticketRows);
        report.append("\n\nPublished result versions: ").append(scalar("SELECT COUNT(*) FROM draw_results WHERE status='PUBLISHED'"));
        return report.toString();
    }

    private long scalar(String sql){Long value=jdbc.queryForObject(sql,Long.class);return value==null?0:value;}
    private static void appendCounts(StringBuilder target,List<CountRow> rows){StringJoiner join=new StringJoiner(", ");rows.forEach(row->join.add(row.name+" "+row.count));target.append(join.length()==0?"none":join);}
    private static void appendRows(StringBuilder target,List<CountRow> rows){if(rows.isEmpty())target.append("\nnone");else for(CountRow row:rows)target.append("\n").append(row.name).append(": ").append(row.count);}
    private record CountRow(String name,long count){}
    private record AmountRow(String name,String currency,long count,BigDecimal amount){}
}
