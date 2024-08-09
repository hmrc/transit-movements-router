/*
 * Copyright 2023 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.transitmovementsrouter.connectors

import org.apache.pekko.pattern.CircuitBreaker
import org.apache.pekko.stream.Materializer
import play.api.Logging
import uk.gov.hmrc.transitmovementsrouter.config.EISInstanceConfig

import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.util.Try

trait CircuitBreakers { self: Logging =>
  def materializer: Materializer
  def code: String
  def eisInstanceConfig: EISInstanceConfig

  private val clazz = getClass.getSimpleName

  def withCircuitBreaker[T](defineFailureFn: Try[T] => Boolean)(block: => Future[T]): Future[T] =
    circuitBreaker.withCircuitBreaker(block, defineFailureFn)

  private lazy val circuitBreaker = new CircuitBreaker(
    scheduler = materializer.system.scheduler,
    maxFailures = eisInstanceConfig.circuitBreaker.maxFailures,
    callTimeout = eisInstanceConfig.circuitBreaker.callTimeout,
    resetTimeout = eisInstanceConfig.circuitBreaker.resetTimeout,
    maxResetTimeout = eisInstanceConfig.circuitBreaker.maxResetTimeout,
    exponentialBackoffFactor = eisInstanceConfig.circuitBreaker.exponentialBackoffFactor,
    randomFactor = eisInstanceConfig.circuitBreaker.randomFactor
  )(materializer.executionContext)
    .onOpen(logger.error(s"$code Circuit breaker for $clazz opening due to failures"))
    .onHalfOpen(logger.warn(s"$code Circuit breaker for $clazz resetting after failures"))
    .onClose {
      logger.warn(
        s"$code Circuit breaker for $clazz closing after trial connection success"
      )
    }
    .onCallFailure(
      _ => logger.error(s"$code Circuit breaker for $clazz recorded failed call")
    )
    .onCallBreakerOpen {
      logger.error(
        s"$code Circuit breaker for $clazz rejected call due to previous failures"
      )
    }
    .onCallTimeout {
      elapsed =>
        val duration = Duration.fromNanos(elapsed)
        logger.error(
          s"$code Circuit breaker for $clazz recorded failed call due to timeout after ${duration.toMillis}ms"
        )
    }
}
