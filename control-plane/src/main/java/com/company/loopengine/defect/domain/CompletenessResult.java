package com.company.loopengine.defect.domain;

import java.util.List;

public record CompletenessResult(IssueFacts facts, List<String> missingFields) {
    public boolean complete() {
        return missingFields.isEmpty();
    }
}
