package io.github.lilaschuda.guanaco.scala3

import io.github.lilaschuda.guanaco.api.RouteOutcome
import org.apache.camel.Exchange

import scala.reflect.ClassTag

/** See guanaco-scala_2.13's RouteOutcomeBodyAsOps for the full rationale. */
extension (outcome: RouteOutcome[?])
  def bodyAs[T](using ct: ClassTag[T]): T =
    val value = outcome.body()
    if value == null then
      throw new IllegalStateException(s"Expected body of type ${ct.runtimeClass.getSimpleName}, but body() was null")
    if !ct.runtimeClass.isInstance(value) then
      throw new IllegalStateException(
        s"Expected body of type ${ct.runtimeClass.getSimpleName}, got ${value.getClass.getSimpleName}"
      )
    value.asInstanceOf[T]

/** See guanaco-scala_2.13's ExchangeBodyAsOps for the full rationale. */
extension (exchange: Exchange)
  def bodyAs[T](using ct: ClassTag[T]): T =
    val value = exchange.getIn.getBody(ct.runtimeClass.asInstanceOf[Class[T]])
    if value == null then
      throw new IllegalStateException(
        s"Expected Exchange body of type ${ct.runtimeClass.getSimpleName}, but getBody() returned null"
      )
    value
