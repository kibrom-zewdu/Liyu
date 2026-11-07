package com.liyulottery.campaign;

import java.math.BigDecimal;
import java.time.Instant;

public record CampaignView(long id, String code, String title, String description, BigDecimal ticketPrice,
                           String currency, Instant salesStartAt, Instant salesCloseAt, Instant drawAt,
                           CampaignStatus status, String promotionalImageFileId) { }
