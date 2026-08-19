package com.zenzmoney.core.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.zenzmoney.common.domain.IntentType;
import com.zenzmoney.common.domain.TransactionType;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class ParsedIntent {

    private IntentType intent = IntentType.UNKNOWN;

    private TransactionType txnType;

    private Long amountMinor;

    private String currency;

    private String categoryId;

    private String categoryName;

    private String categoryGuess;

    private Long txnDate;

    private String payeeName;

    private String note;

    private double confidence;

    private List<String> missingFields = new ArrayList<>();

    @JsonIgnore
    public boolean isComplete() {
        return missingFields.isEmpty();
    }
}
