package com.liyulottery;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

import java.util.Locale;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class FoundationTest {
    @Test
    void allInitialLocalesHaveTheBrandAndGenericErrorMessage() throws Exception {
        for (String language : new String[]{"en", "am", "ti"}) {
            Properties messages = PropertiesLoaderUtils.loadProperties(
                    new ClassPathResource("i18n/messages_" + language + ".properties"));
            assertEquals("LIYU LOTTERY", messages.getProperty("app.name"));
            assertFalse(messages.getProperty("error.generic", "").isBlank(), language);
        }
    }
}
