package uk.gov.hmrc.transitmovementsrouter.connectors

import akka.pattern.CircuitBreaker
import akka.stream.Materializer
import play.api.Logging
import uk.gov.hmrc.transitmovementsrouter.config.AppConfig
import uk.gov.hmrc.transitmovementsrouter.config.CircuitBreakerConfig
import uk.gov.hmrc.transitmovementsrouter.models.RoutingOption
import uk.gov.hmrc.transitmovementsrouter.models.RoutingOption.Gb
import uk.gov.hmrc.transitmovementsrouter.models.RoutingOption.Ni

import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.util.Try

trait CircuitBreakers { self: Logging =>
  def materializer: Materializer
  def appConfig: AppConfig

  private val clazz = getClass.getSimpleName

  def withCircuitBreaker[T](routingOption: RoutingOption, defineFailureFn: Try[T] => Boolean)(block: => Future[T]): Future[T] =
    (routingOption match {
      case Gb => gbCircuitBreaker
      case Ni => niCircuitBreaker
    }).withCircuitBreaker(block, defineFailureFn)

  lazy val gbCircuitBreaker = new CircuitBreaker(
    scheduler = materializer.system.scheduler,
    maxFailures = appConfig.eisGb.circuitBreaker.maxFailures,
    callTimeout = appConfig.eisGb.circuitBreaker.callTimeout,
    resetTimeout = appConfig.eisGb.circuitBreaker.resetTimeout,
    maxResetTimeout = appConfig.eisGb.circuitBreaker.maxResetTimeout,
    exponentialBackoffFactor = appConfig.eisGb.circuitBreaker.exponentialBackoffFactor,
    randomFactor = appConfig.eisGb.circuitBreaker.randomFactor
  )(materializer.executionContext)
    .onOpen(logger.error(s"GB Circuit breaker for $clazz opening due to failures"))
    .onHalfOpen(logger.warn(s"GB Circuit breaker for $clazz resetting after failures"))
    .onClose {
      logger.warn(
        s"GB Circuit breaker for $clazz closing after trial connection success"
      )
    }
    .onCallFailure(
      _ => logger.error(s"GB Circuit breaker for $clazz recorded failed call")
    )
    .onCallBreakerOpen {
      logger.error(
        s"GB Circuit breaker for $clazz rejected call due to previous failures"
      )
    }
    .onCallTimeout {
      elapsed =>
        val duration = Duration.fromNanos(elapsed)
        logger.error(
          s"GB Circuit breaker for $clazz recorded failed call due to timeout after ${duration.toMillis}ms"
        )
    }

  lazy val niCircuitBreaker = new CircuitBreaker(
    scheduler = materializer.system.scheduler,
    maxFailures = appConfig.eisNi.circuitBreaker.maxFailures,
    callTimeout = appConfig.eisNi.circuitBreaker.callTimeout,
    resetTimeout = appConfig.eisNi.circuitBreaker.resetTimeout,
    maxResetTimeout = appConfig.eisNi.circuitBreaker.maxResetTimeout,
    exponentialBackoffFactor = appConfig.eisNi.circuitBreaker.exponentialBackoffFactor,
    randomFactor = appConfig.eisNi.circuitBreaker.randomFactor
  )(materializer.executionContext)
    .onOpen(logger.error(s"NI Circuit breaker for $clazz opening due to failures"))
    .onHalfOpen(logger.warn(s"NI Circuit breaker for $clazz resetting after failures"))
    .onClose {
      logger.warn(
        s"NI Circuit breaker for $clazz closing after trial connection success"
      )
    }
    .onCallFailure(
      _ => logger.error(s"NI Circuit breaker for $clazz recorded failed call")
    )
    .onCallBreakerOpen {
      logger.error(
        s"NI Circuit breaker for $clazz rejected call due to previous failures"
      )
    }
    .onCallTimeout {
      elapsed =>
        val duration = Duration.fromNanos(elapsed)
        logger.error(
          s"NI Circuit breaker for $clazz recorded failed call due to timeout after ${duration.toMillis}ms"
        )
    }

}
