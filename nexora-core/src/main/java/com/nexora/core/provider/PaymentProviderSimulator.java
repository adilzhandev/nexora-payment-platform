package com.nexora.core.provider;

import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
public class PaymentProviderSimulator {
    public ProviderResult process(ProviderScenario scenario) {
        return switch (scenario) {
            case SUCCESS -> new ProviderResult(true, "APPROVED", 1);
            case DECLINED -> new ProviderResult(false, "DECLINED", 1);
            case DELAYED_SUCCESS -> delayedSuccess();
            case TIMEOUT -> new ProviderResult(false, "PROVIDER_TIMEOUT", 1);
            case NETWORK_ERROR -> new ProviderResult(false, "NETWORK_ERROR", 1);
            case DUPLICATE_NOTIFICATION -> new ProviderResult(true, "APPROVED", 2);
        };
    }

    private ProviderResult delayedSuccess() {
        try {
            Thread.sleep(Duration.ofMillis(100));
            return new ProviderResult(true, "APPROVED", 1);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new ProviderResult(false, "INTERRUPTED", 1);
        }
    }
}
