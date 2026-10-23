package com.liyulottery.support;

import com.liyulottery.common.TranslationService;
import com.liyulottery.telegram.TelegramApiClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@ConditionalOnProperty(prefix="app.telegram",name="enabled",havingValue="true")
public class SupportAdminHandler {
    private final SupportService support;
    private final TelegramApiClient telegram;
    private final TranslationService translations;
    public SupportAdminHandler(SupportService support,TelegramApiClient telegram,TranslationService translations){this.support=support;this.telegram=telegram;this.translations=translations;}

    public Optional<String> handle(long telegramAgentId,String text){
        if(text==null||!(text.startsWith("/supportopen")||text.startsWith("/supportreply")||text.startsWith("/supportclose")))return Optional.empty();
        try {
            String[] args=text.trim().split("\\s+",3);
            if(args[0].equals("/supportopen")){
                var requests=support.openRequests(telegramAgentId);
                if(requests.isEmpty())return Optional.of("There are no open support requests.");
                StringBuilder message=new StringBuilder("Open support requests (latest activity first):");
                for(var r:requests)message.append("\n#").append(r.id()).append(" — Telegram ").append(r.telegramUserId()).append(" — ").append(r.displayName()==null?"customer":r.displayName()).append(" — ").append(r.subject());
                return Optional.of(message.toString());
            }
            if(args.length<2)return Optional.of("Use /supportreply REQUEST_ID message or /supportclose REQUEST_ID");
            long requestId=Long.parseLong(args[1]);
            if(args[0].equals("/supportclose")){
                support.close(telegramAgentId,requestId);
                return Optional.of("Support request closed.");
            }
            if(args.length<3)return Optional.of("Use /supportreply REQUEST_ID message");
            var customer=support.reply(telegramAgentId,requestId,args[2]);
            try { telegram.sendMessage(customer.telegramUserId(),translations.text("support.agent_reply",customer.language(),args[2]),java.util.Map.of("remove_keyboard",true)); }
            catch(RuntimeException deliveryFailure){return Optional.of("Reply saved, but Telegram delivery failed. Use /supportopen and retry with a new reply after checking the customer chat.");}
            return Optional.of("Reply sent to the customer.");
        } catch(AccessDeniedException denied){return Optional.of("This account is not authorized for support operations.");}
        catch(RuntimeException failure){return Optional.of("Support command failed: "+failure.getMessage());}
    }
}
