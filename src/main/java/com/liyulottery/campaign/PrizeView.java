package com.liyulottery.campaign;

import java.math.BigDecimal;

public record PrizeView(long id, String name, String description, String category, int quantity,
                        BigDecimal monetaryValue, String imageFileId) { }
