package com.liyulottery;

import com.liyulottery.campaign.CampaignStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CampaignStatusTest {
    @Test
    void permitsExpectedCampaignProgression() {
        assertTrue(CampaignStatus.DRAFT.canTransitionTo(CampaignStatus.SCHEDULED));
        assertTrue(CampaignStatus.SCHEDULED.canTransitionTo(CampaignStatus.OPEN));
        assertTrue(CampaignStatus.OPEN.canTransitionTo(CampaignStatus.CLOSED));
        assertTrue(CampaignStatus.CLOSED.canTransitionTo(CampaignStatus.DRAW_PENDING));
        assertTrue(CampaignStatus.DRAW_PENDING.canTransitionTo(CampaignStatus.COMPLETED));
    }

    @Test
    void preventsReopeningCompletedAndCancelledCampaigns() {
        assertFalse(CampaignStatus.COMPLETED.canTransitionTo(CampaignStatus.OPEN));
        assertFalse(CampaignStatus.CANCELLED.canTransitionTo(CampaignStatus.OPEN));
        assertFalse(CampaignStatus.CLOSED.canTransitionTo(CampaignStatus.OPEN));
    }
}
