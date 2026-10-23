package com.liyulottery.settings;

import com.liyulottery.admin.AdminAuthorizationService;
import com.liyulottery.admin.AdminRole;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class PublicSettingsService {
    private static final Set<String> ALLOWED=Set.of("business_name","support_contact","privacy.en","privacy.am","privacy.ti");
    private final JdbcTemplate jdbc;
    private final AdminAuthorizationService authorization;
    public PublicSettingsService(JdbcTemplate jdbc,AdminAuthorizationService authorization){this.jdbc=jdbc;this.authorization=authorization;}

    @Transactional
    public void set(long actorTelegramId,String key,String value) {
        long actor=authorization.requireRole(actorTelegramId,AdminRole.SUPER_ADMIN);
        if(!ALLOWED.contains(key))throw new IllegalArgumentException("Allowed keys: "+String.join(", ",ALLOWED));
        if(value==null||value.isBlank()||value.length()>4000)throw new IllegalArgumentException("Setting value must contain 1–4000 characters.");
        String before=jdbc.query("SELECT setting_value FROM app_settings WHERE setting_key=?",rs->rs.next()?rs.getString(1):null,key);
        jdbc.update("INSERT INTO app_settings(setting_key,setting_value,is_sensitive,updated_by_admin_id) VALUES(?,?,FALSE,?) ON DUPLICATE KEY UPDATE setting_value=VALUES(setting_value),is_sensitive=FALSE,updated_by_admin_id=VALUES(updated_by_admin_id)",key,value.trim(),actor);
        jdbc.update("INSERT INTO audit_logs(actor_admin_id,action,target_type,target_id,before_json,after_json) VALUES(?,?,?,?,JSON_OBJECT('value',?),JSON_OBJECT('value',?))",actor,"PUBLIC_SETTING_UPDATED","APP_SETTING",key,before,value.trim());
    }

    @Transactional(readOnly=true)
    public Optional<String> get(long actorTelegramId,String key) {
        authorization.requireRole(actorTelegramId,AdminRole.SUPER_ADMIN);
        if(!ALLOWED.contains(key))throw new IllegalArgumentException("Setting key is not supported.");
        return value(key);
    }

    @Transactional(readOnly=true)
    public Optional<String> privacyNotice(String language) {
        Optional<String> localized=value("privacy."+language);
        return localized.isPresent()?localized:value("privacy.en");
    }

    private Optional<String> value(String key) {
        return jdbc.query("SELECT setting_value FROM app_settings WHERE setting_key=? AND is_sensitive=FALSE",rs->rs.next()?Optional.of(rs.getString(1)):Optional.empty(),key);
    }
}
