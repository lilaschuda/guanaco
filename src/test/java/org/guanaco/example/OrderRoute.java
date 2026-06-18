package org.guanaco.example;

import io.github.lilaschuda.guanaco.core.RouteOutcome;

/**
 * Declares all possible routing outcomes for the OrderProcessor.
 * The compiler enforces that every branch is covered.
 */
public sealed interface OrderRoute<T> extends RouteOutcome permits ToInventory, ToPayment, ToFraudCheck {
    T body();
}
