package com.liyulottery.draw;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;

@Service
@ConditionalOnProperty(prefix="app.telegram",name="enabled",havingValue="true")
public class DrawResultAdminHandler {
    private final DrawResultService results;
    public DrawResultAdminHandler(DrawResultService results){this.results=results;}

    public Optional<String> handle(long telegramUserId,String text) {
        if(text==null||!(text.startsWith("/resulthelp")||text.startsWith("/resultprepare")||text.startsWith("/resultpublish"))) return Optional.empty();
        try {
            if(text.startsWith("/resulthelp")) return Optional.of("Results commands (RESULTS_MANAGER):\n/resultprepare CAMPAIGN_ID notice_en | notice_am | notice_ti || TICKET_NUMBER:PRIZE_ID,TICKET_NUMBER:PRIZE_ID\n/resultpublish RESULT_ID CONFIRM\nOnly enter officially confirmed winning tickets. Corrections require a new draft version; published history is retained.");
            if(text.startsWith("/resultprepare")) {
                String[] halves=text.split("\\|\\|",2);
                if(halves.length!=2) return Optional.of("Use /resultprepare CAMPAIGN_ID English notice | Amharic notice | Tigrigna notice || TICKET_NUMBER:PRIZE_ID,...");
                String[] head=halves[0].trim().split("\\s+",3);
                if(head.length!=3) return Optional.of("Enter the campaign ID and all three reviewed notices before ||.");
                String[] notices=head[2].split("\\|",-1);
                if(notices.length!=3) return Optional.of("Provide exactly three reviewed notices separated by | in English, Amharic, and Tigrigna.");
                long resultId=results.prepare(telegramUserId,Long.parseLong(head[1]),Map.of("en",notices[0].trim(),"am",notices[1].trim(),"ti",notices[2].trim()),halves[1].trim());
                return Optional.of("Draft official result "+resultId+" prepared. Review the campaign and ticket/prize mapping, then publish with /resultpublish "+resultId+" CONFIRM.");
            }
            String[] args=text.trim().split("\\s+");
            if(args.length!=3||!"CONFIRM".equals(args[2])) return Optional.of("Explicit confirmation required: /resultpublish RESULT_ID CONFIRM");
            results.publish(telegramUserId,Long.parseLong(args[1]));
            return Optional.of("Official results published. Customers can now view /results. A correction must be prepared as a new version.");
        } catch(RuntimeException failure) { return Optional.of("Result operation failed: "+failure.getMessage()); }
    }
}
