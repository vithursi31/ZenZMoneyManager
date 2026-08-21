package com.zenzmoney.core.web.dto;

import lombok.Getter;

/**
 * The result of creating a template: the template itself, plus the ledger row it
 * posted immediately when its first run was already due. {@code posted} is null for
 * a template scheduled in the future — the ordinary case.
 */
@Getter
public class RecurringCreatedResponse {

    private final RecurringResponse template;
    private final TransactionResponse posted;

    public RecurringCreatedResponse(RecurringResponse template, TransactionResponse posted) {
        this.template = template;
        this.posted = posted;
    }
}
