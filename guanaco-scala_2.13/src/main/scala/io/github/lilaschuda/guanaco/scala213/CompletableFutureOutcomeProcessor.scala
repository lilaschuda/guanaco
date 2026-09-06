package io.github.lilaschuda.guanaco.scala213

import io.github.lilaschuda.guanaco.api.{AsyncOutcomeProcessor, OutcomeCallback}
import org.apache.camel.Exchange

import java.util.concurrent.{CompletableFuture, CompletionException}

/**
 * Scala-idiomatic bridge from a CompletableFuture-returning route method to
 * Guanaco's AsyncOutcomeProcessor/OutcomeCallback contract, so a Scala route
 * author never has to hand-wire `.whenComplete` themselves.
 *
 * Deliberately targets java.util.concurrent.CompletableFuture rather than a
 * specific effect system (cats-effect IO, ZIO): CompletableFuture is Camel's
 * own native async currency (AsyncProcessor.processAsync already returns
 * CompletableFuture<Exchange>), and both major Scala effect libraries already
 * ship maintained conversions from CompletableFuture into their own effect
 * type (cats-effect's Async#fromCompletableFuture, ZIO's
 * ZIO.fromCompletionStage). Guanaco doesn't need to pick a side or take on
 * either as a dependency.
 *
 * Unwraps CompletionException before calling onFailure: a future built via
 * chaining combinators (thenApply, thenCompose, etc.) wraps a thrown
 * exception in a CompletionException, while a future failed directly via
 * completeExceptionally(cause) does not -- since callers may build processF's
 * future either way, this normalizes both to the real cause rather than
 * leaking JDK plumbing into route-level error handling.
 *
 * Usage:
 * {{{
 * class MyRoute extends CompletableFutureOutcomeProcessor[MyOutcome] {
 *   override def processF(exchange: Exchange): CompletableFuture[MyOutcome] = ...
 * }
 * }}}
 */
trait CompletableFutureOutcomeProcessor[R] extends AsyncOutcomeProcessor[R] {

  def processF(exchange: Exchange): CompletableFuture[R]

  final override def process(exchange: Exchange, callback: OutcomeCallback[R]): Unit = {
    val _ = processF(exchange).whenComplete { (result, error) =>
      if (error != null) callback.onFailure(unwrapCompletionException(error))
      else callback.onOutcome(result)
    }
  }

  private def unwrapCompletionException(error: Throwable): Throwable = error match {
    case ce: CompletionException if ce.getCause != null => ce.getCause
    case other => other
  }
}
