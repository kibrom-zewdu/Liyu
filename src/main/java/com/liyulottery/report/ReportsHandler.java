package com.liyulottery.report;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@ConditionalOnProperty(prefix="app.telegram",name="enabled",havingValue="true")
public class ReportsHandler {
    private final ReportsService reports;
    public ReportsHandler(ReportsService reports){this.reports=reports;}
    public Optional<String> handle(long telegramUserId,String text){
        if(text==null||!text.startsWith("/reports"))return Optional.empty();
        try{return Optional.of(reports.summary(telegramUserId));}
        catch(AccessDeniedException denied){return Optional.of("Only a super administrator can view the complete operational report.");}
        catch(RuntimeException failure){return Optional.of("The report could not be generated.");}
    }
}
