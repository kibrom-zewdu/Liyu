package com.liyulottery.payment;

import com.liyulottery.order.OrderService;
import com.liyulottery.telegram.TelegramApiClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@ConditionalOnProperty(prefix="app.telegram",name="enabled",havingValue="true")
public class PaymentReviewHandler {
    private final OrderService orders;
    private final TelegramApiClient telegram;
    public PaymentReviewHandler(OrderService orders,TelegramApiClient telegram){this.orders=orders;this.telegram=telegram;}

    public Optional<String> handle(long telegramUserId,long chatId,String text) {
        if(text==null||!(text.startsWith("/pendingpayments")||text.startsWith("/paymentapprove")||text.startsWith("/paymentreject"))) return Optional.empty();
        String[] parts=text.trim().split("\\s+",4);
        try {
            if(parts[0].startsWith("/pendingpayments")) {
                List<OrderService.PendingPayment> pending=orders.pendingPayments(telegramUserId);
                if(pending.isEmpty()) return Optional.of("No receipts are awaiting review.");
                for(var item:pending){
                    telegram.sendMessage(chatId,"Order "+item.reference()+" — "+item.quantity()+" tickets — "+item.total()+" "+item.currency()+" — customer "+item.customerTelegramId()+"\nApprove: /paymentapprove "+item.reference()+" VERIFIED_TRANSACTION_REFERENCE [note]\nReject: /paymentreject "+item.reference()+" reason",Map.of("remove_keyboard",true));
                    telegram.sendPhoto(chatId,item.fileId(),"Receipt for "+item.reference()+". Verify independently against the official payment account.");
                }
                return Optional.of("Listed "+pending.size()+" pending receipt(s). A receipt image is not payment verification.");
            }
            if(parts.length<3) return Optional.of("Usage: /paymentapprove ORDER_REFERENCE TRANSACTION_REFERENCE [note] or /paymentreject ORDER_REFERENCE reason");
            boolean approve=parts[0].startsWith("/paymentapprove");
            String transaction=approve?parts[2]:null;
            String note=approve?(parts.length==4?parts[3]:null):String.join(" ",java.util.Arrays.copyOfRange(parts,2,parts.length));
            orders.decide(telegramUserId,parts[1],approve,transaction,note);
            return Optional.of(approve?"Payment verified and approved. Ticket allocation will occur in the ticket phase.":"Payment rejected. No tickets were issued.");
        } catch(AccessDeniedException denied){ return Optional.of("This account is not authorized to review payments."); }
        catch(RuntimeException failure){ return Optional.of("Payment review failed: "+failure.getMessage()); }
    }
}
