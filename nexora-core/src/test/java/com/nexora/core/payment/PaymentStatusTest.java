package com.nexora.core.payment;

import com.nexora.core.error.DomainException;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.*;

class PaymentStatusTest {
    @ParameterizedTest
    @MethodSource("transitions")
    void onlyDocumentedTransitionsAreAllowed(PaymentStatus source, PaymentStatus target, boolean allowed) {
        if (allowed) {
            assertThatCode(() -> source.requireTransitionTo(target)).doesNotThrowAnyException();
        } else {
            assertThatThrownBy(() -> source.requireTransitionTo(target)).isInstanceOf(DomainException.class);
        }
    }

    static Stream<Arguments> transitions() {
        return Stream.of(PaymentStatus.values()).flatMap(source -> Stream.of(PaymentStatus.values()).map(target ->
                Arguments.of(source, target, source == PaymentStatus.CREATED && (target == PaymentStatus.PROCESSING || target == PaymentStatus.CANCELLED)
                        || source == PaymentStatus.PROCESSING && (target == PaymentStatus.SUCCEEDED || target == PaymentStatus.FAILED))));
    }
}
