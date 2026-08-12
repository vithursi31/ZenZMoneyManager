package com.zenzmoney.core.web.dto;

import com.zenzmoney.common.domain.GoalStatus;
import com.zenzmoney.core.entity.SavingsGoal;
import lombok.Getter;

/**
 * A savings goal plus its derived progress. {@code saved} is summed from
 * {@link com.zenzmoney.core.entity.GoalContribution} rows and never stored (§1.9),
 * so the goal can never diverge from the ledger. {@code remaining} floors at 0.
 */
@Getter
public class GoalResponse {

    private final String id;
    private final String name;
    private final long targetAmount;
    private final String currency;
    private final Long targetDate;
    private final GoalStatus status;
    private final String color;
    private final String icon;

    // derived
    private final long saved;
    private final long remaining;

    private GoalResponse(SavingsGoal g, long saved) {
        this.id = g.getId();
        this.name = g.getName();
        this.targetAmount = g.getTargetAmount();
        this.currency = g.getCurrency();
        this.targetDate = g.getTargetDate();
        this.status = g.getStatus();
        this.color = g.getColor();
        this.icon = g.getIcon();
        this.saved = saved;
        this.remaining = Math.max(0, g.getTargetAmount() - saved);
    }

    public static GoalResponse of(SavingsGoal g, long saved) {
        return new GoalResponse(g, saved);
    }
}
