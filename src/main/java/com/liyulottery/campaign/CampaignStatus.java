package com.liyulottery.campaign;

public enum CampaignStatus {
    DRAFT, SCHEDULED, OPEN, CLOSED, DRAW_PENDING, COMPLETED, CANCELLED;

    public boolean canTransitionTo(CampaignStatus next) {
        return switch (this) {
            case DRAFT -> next == SCHEDULED || next == CANCELLED;
            case SCHEDULED -> next == OPEN || next == CLOSED || next == CANCELLED;
            case OPEN -> next == CLOSED || next == CANCELLED;
            case CLOSED -> next == DRAW_PENDING || next == COMPLETED || next == CANCELLED;
            case DRAW_PENDING -> next == COMPLETED || next == CANCELLED;
            case COMPLETED, CANCELLED -> false;
        };
    }
}
