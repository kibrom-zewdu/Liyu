package com.liyulottery.settings;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@ConditionalOnProperty(prefix="app.telegram",name="enabled",havingValue="true")
public class PublicSettingsHandler {
    private final PublicSettingsService settings;
    public PublicSettingsHandler(PublicSettingsService settings){this.settings=settings;}
    public Optional<String> handle(long telegramAdminId,String text){
        if(text==null)return Optional.empty();
        String[] parts=text.trim().split("\\s+",3);
        if(!(parts[0].equals("/settings")||parts[0].equals("/settingget")||parts[0].equals("/settingset")))return Optional.empty();
        try {
            if(parts[0].equals("/settings"))return Optional.of("Public settings (SUPER_ADMIN only):\n/settingset KEY VALUE\n/settingget KEY\nAllowed keys: business_name, support_contact, privacy.en, privacy.am, privacy.ti.\nUse only public business information; payment credentials and secrets are not accepted.");
            if(parts.length<2)return Optional.of("A setting key is required.");
            if(parts[0].equals("/settingget"))return Optional.of(parts[1]+" = "+settings.get(telegramAdminId,parts[1]).orElse("not configured"));
            if(parts.length<3)return Optional.of("Use /settingset KEY VALUE.");
            settings.set(telegramAdminId,parts[1],parts[2]);
            return Optional.of("Public setting saved and audited.");
        }catch(AccessDeniedException denied){return Optional.of("Only a super administrator can update public settings.");}
        catch(RuntimeException failure){return Optional.of("Setting update failed: "+failure.getMessage());}
    }
}
