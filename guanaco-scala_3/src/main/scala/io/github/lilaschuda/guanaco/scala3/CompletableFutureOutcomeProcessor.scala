package io.github.lilaschuda.guanaco.scala3

import io.github.lilaschuda.guanaco.api.{AsyncOutcomeProcessor, OutcomeCallback}
import org.apache.camel.Exchange

import java.util.concurrent.{CompletableFuture, CompletionException}

/** See guanaco-scala_2.13's CompletableFutureOutcomeProcessor for the full rationale. */
trait CompletableFutureOutcomeProcessor[R] extends AsyncOutcomeProcessor[R]:

  def processF(exchange: Exchange): CompletableFuture[R]

  final override def process(exchange: Exchange, callback: OutcomeCallback[R]): Unit =
    val _ = processF(exchange).whenComplete { (result, error) =>
      if error != null then callback.onFailure(unwrapCompletionException(error))
      else callback.onOutcome(result)
    }

  private def unwrapCompletionException(error: Throwable): Throwable = error match
    case ce: CompletionException if ce.getCause != null => ce.getCause
    case other => other
