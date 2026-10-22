package com.liyulottery.common;

import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
public class TranslationService {
    private final MessageSource messageSource;

    public TranslationService(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    public String text(String key, String languageCode, Object... arguments) {
        String safeLanguage = CustomerLanguage.fromCode(languageCode).map(CustomerLanguage::code).orElse("en");
        String translated = messageSource.getMessage(key, arguments, null, Locale.forLanguageTag(safeLanguage));
        return translated != null ? translated : messageSource.getMessage(key, arguments, Locale.ENGLISH);
    }
}
