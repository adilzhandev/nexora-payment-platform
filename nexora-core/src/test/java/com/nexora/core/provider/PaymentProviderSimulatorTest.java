package com.nexora.core.provider;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.*;

class PaymentProviderSimulatorTest {
    private final PaymentProviderSimulator simulator = new PaymentProviderSimulator();

    @ParameterizedTest
    @CsvSource({"SUCCESS,true,APPROVED,1", "DECLINED,false,DECLINED,1", "DELAYED_SUCCESS,true,APPROVED,1",
            "TIMEOUT,false,PROVIDER_TIMEOUT,1", "NETWORK_ERROR,false,NETWORK_ERROR,1", "DUPLICATE_NOTIFICATION,true,APPROVED,2"})
    void scenariosHaveDeterministicResults(ProviderScenario scenario, boolean succeeded, String code, int notifications) {
        assertThat(simulator.process(scenario)).isEqualTo(new ProviderResult(succeeded, code, notifications));
    }
}
