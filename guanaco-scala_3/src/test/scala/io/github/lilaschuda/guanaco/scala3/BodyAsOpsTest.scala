package io.github.lilaschuda.guanaco.scala3

import io.github.lilaschuda.guanaco.api.RouteOutcome
import org.apache.camel.impl.DefaultCamelContext
import org.apache.camel.support.DefaultExchange
import org.assertj.core.api.Assertions.{assertThat, catchThrowable}
import org.junit.jupiter.api.Test

final case class BodyAsFixture(value: String) extends RouteOutcome[String]:
  override def body(): String = value

final case class NullBodyFixture() extends RouteOutcome[String]:
  override def body(): String = null

class BodyAsOpsTest:

  @Test
  def routeOutcomeBodyAsReturnsTypedBodyWhenMatching(): Unit =
    val outcome: RouteOutcome[?] = BodyAsFixture("payload")

    assertThat(outcome.bodyAs[String]).isEqualTo("payload")

  @Test
  def routeOutcomeBodyAsThrowsWhenBodyIsNull(): Unit =
    val outcome: RouteOutcome[?] = NullBodyFixture()

    val thrown = catchThrowable(() => outcome.bodyAs[String])

    assertThat(thrown).isInstanceOf(classOf[IllegalStateException])
    assertThat(thrown.getMessage).contains("was null")

  @Test
  def routeOutcomeBodyAsThrowsWhenBodyIsWrongType(): Unit =
    val outcome: RouteOutcome[?] = BodyAsFixture("payload")

    val thrown = catchThrowable(() => outcome.bodyAs[Integer])

    assertThat(thrown).isInstanceOf(classOf[IllegalStateException])
    assertThat(thrown.getMessage).contains("Expected body of type")

  @Test
  def exchangeBodyAsReturnsTypedBodyWhenPresent(): Unit =
    val context = new DefaultCamelContext()
    val exchange = new DefaultExchange(context)
    exchange.getIn.setBody("payload")

    assertThat(exchange.bodyAs[String]).isEqualTo("payload")

  @Test
  def exchangeBodyAsThrowsWhenBodyIsNull(): Unit =
    val context = new DefaultCamelContext()
    val exchange = new DefaultExchange(context)
    exchange.getIn.setBody(null)

    val thrown = catchThrowable(() => exchange.bodyAs[String])

    assertThat(thrown).isInstanceOf(classOf[IllegalStateException])
    assertThat(thrown.getMessage).contains("getBody() returned null")
