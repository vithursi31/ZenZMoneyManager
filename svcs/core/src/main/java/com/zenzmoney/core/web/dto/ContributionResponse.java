package com.zenzmoney.core.web.dto;

import com.zenzmoney.core.entity.GoalContribution;
import lombok.Getter;

@Getter
public class ContributionResponse {

    private final String id;
    private final String goalId;
    private final String transactionId;
    private final long amount;
    private final long contributedAt;
    private final String note;

    private ContributionResponse(GoalContribution c) {
        this.id = c.getId();
        this.goalId = c.getGoalId();
        this.transactionId = c.getTransactionId();
        this.amount = c.getAmount();
        this.contributedAt = c.getContributedAt();
        this.note = c.getNote();
    }

    public static ContributionResponse of(GoalContribution c) {
        return new ContributionResponse(c);
    }
}
