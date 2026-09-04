package io.github.lilaschuda.guanaco.api;

/**
 * Reports the eventual result of an {@link AsyncOutcomeProcessor} invocation.
 *
 * @param <R> the sealed route interface this callback reports outcomes for --
 *        matches {@link AsyncOutcomeProcessor}'s own type parameter
 */
public interface OutcomeCallback<R> {

    /**
     * Reports a successfully computed routing decision.
     *
     * @param outcome the computed routing outcome
     */
    void onOutcome(R outcome);

    /**
     * Reports that computing the routing decision failed.
     *
     * @param error the failure
     */
    void onFailure(Throwable error);
}