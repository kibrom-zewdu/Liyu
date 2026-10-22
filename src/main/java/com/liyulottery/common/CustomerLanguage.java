package com.liyulottery.common;

import java.util.Arrays;
import java.util.Optional;

public enum CustomerLanguage {
    ENGLISH("en", "🇬🇧 English"),
    AMHARIC("am", "🇪🇹 አማርኛ"),
    TIGRIGNA("ti", "🇪🇹 ትግርኛ");

    private final String code;
    private final String buttonText;

    CustomerLanguage(String code, String buttonText) {
        this.code = code;
        this.buttonText = buttonText;
    }

    public String code() { return code; }
    public String buttonText() { return buttonText; }

    public static Optional<CustomerLanguage> fromCode(String value) {
        return Arrays.stream(values()).filter(language -> language.code.equalsIgnoreCase(value)).findFirst();
    }

    public static Optional<CustomerLanguage> fromButtonText(String value) {
        return Arrays.stream(values()).filter(language -> language.buttonText.equals(value)).findFirst();
    }
}
