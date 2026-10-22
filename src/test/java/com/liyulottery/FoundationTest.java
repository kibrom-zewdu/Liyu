package com.liyulottery;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import com.liyulottery.common.CustomerLanguage;

import java.util.Locale;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class FoundationTest {
    @Test
    void allInitialLocalesHaveTheBrandAndGenericErrorMessage() throws Exception {
        String[] requiredKeys = {"app.name", "error.generic", "welcome.choose_language", "welcome.selected",
                "welcome.returning", "language.required", "language.choose", "menu.prizes", "menu.buy",
                "menu.tickets", "menu.draw", "menu.results", "menu.help", "menu.language", "campaign.none",
                "campaign.none_prizes", "campaign.none_buy", "campaign.none_draw", "tickets.empty", "results.none",
                "help.prompt", "help.instructions"};
        for (String language : new String[]{"en", "am", "ti"}) {
            Properties messages = PropertiesLoaderUtils.loadProperties(
                    new ClassPathResource("i18n/messages_" + language + ".properties"));
            assertEquals("LIYU LOTTERY", messages.getProperty("app.name"));
            for (String key : requiredKeys) assertFalse(messages.getProperty(key, "").isBlank(), language + ":" + key);
        }
        assertEquals("en", CustomerLanguage.fromCode("en").orElseThrow().code());
        assertEquals("am", CustomerLanguage.fromButtonText("🇪🇹 አማርኛ").orElseThrow().code());
        assertEquals("ti", CustomerLanguage.fromButtonText("🇪🇹 ትግርኛ").orElseThrow().code());
    }
}
