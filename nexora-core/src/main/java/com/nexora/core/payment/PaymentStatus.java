package com.nexora.core.payment;

import com.nexora.core.error.DomainException;

public enum PaymentStatus {
    CREATED, PROCESSING, SUCCEEDED, FAILED, CANCELLED;

    public void requireTransitionTo(PaymentStatus target) {
        boolean allowed = switch (this) {
            case CREATED -> target == PROCESSING || target == CANCELLED;
            case PROCESSING -> target == SUCCEEDED || target == FAILED;
            case SUCCEEDED, FAILED, CANCELLED -> false;
        };
        if (!allowed) {
            throw DomainException.conflict("Cannot transition payment from " + this + " to " + target);
        }
    }
}
