package io.github.lilaschuda.guanaco.scala213

import io.github.lilaschuda.guanaco.api.OutcomeCallback
import org.apache.camel.Exchange
import org.apache.camel.impl.DefaultCamelContext
import org.apache.camel.support.DefaultExchange
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

import java.util.concurrent.CompletableFuture

final class RecordingCallback[R] extends OutcomeCallback[R] {
  var outcome: Option[R] = None
  var failure: Option[Throwable] = None
  override def onOutcome(o: R): Unit = outcome = Some(o)
  override def onFailure(t: Throwable): Unit = failure = Some(t)
}

final class SuccessProcessor extends CompletableFutureOutcomeProcessor[String] {
  override def processF(exchange: Exchange): CompletableFuture[String] =
    CompletableFuture.completedFuture("done")
}

final class DirectFailureProcessor(cause: Throwable) extends CompletableFutureOutcomeProcessor[String] {
  override def processF(exchange: Exchange): CompletableFuture[String] = {
    val fut = new CompletableFuture[String]()
    fut.completeExceptionally(cause)
    fut
  }
}

final class ChainedFailureProcessor(cause: Throwable) extends CompletableFutureOutcomeProcessor[String] {
  override def processF(exchange: Exchange): CompletableFuture[String] =
    CompletableFuture.completedFuture("ignored").thenApply[String](_ => throw cause)
}

class CompletableFutureOutcomeProcessorTest {

  @Test
  def deliversOutcomeOnSuccessfulFuture(): Unit = {
    val callback = new RecordingCallback[String]
    val exchange = new DefaultExchange(new DefaultCamelContext())

    new SuccessProcessor().process(exchange, callback)

    assertThat(callback.outcome.orNull).isEqualTo("done")
    assertThat(callback.failure.orNull).isNull()
  }

  @Test
  def deliversFailureDirectlyWhenNotWrappedInCompletionException(): Unit = {
    val cause = new RuntimeException("boom")
    val callback = new RecordingCallback[String]
    val exchange = new DefaultExchange(new DefaultCamelContext())

    new DirectFailureProcessor(cause).process(exchange, callback)

    assertThat(callback.failure.orNull).isSameAs(cause)
  }

  @Test
  def unwrapsCompletionExceptionFromChainedFuture(): Unit = {
    val cause = new RuntimeException("boom-chained")
    val callback = new RecordingCallback[String]
    val exchange = new DefaultExchange(new DefaultCamelContext())

    new ChainedFailureProcessor(cause).process(exchange, callback)

    assertThat(callback.failure.orNull).isSameAs(cause)
  }
}
