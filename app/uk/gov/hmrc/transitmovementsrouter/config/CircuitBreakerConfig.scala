package uk.gov.hmrc.transitmovementsrouter.config

import play.api.ConfigLoader
import play.api.Configuration

import scala.concurrent.duration.FiniteDuration

object CircuitBreakerConfig {
  implicit val configLoader: ConfigLoader[CircuitBreakerConfig] = ConfigLoader {
    rootConfig => rootPath =>
      val config = Configuration(rootConfig.getConfig(rootPath))
      CircuitBreakerConfig(
        config.get[Int]("max-failures"),
        config.get[FiniteDuration]("call-timeout"),
        config.get[FiniteDuration]("reset-timeout"),
        config.get[FiniteDuration]("max-reset-timeout"),
        config.get[Double]("exponential-backoff-factor"),
        config.get[Double]("random-factor")
      )
  }
}
case class CircuitBreakerConfig(
 maxFailures: Int,
 callTimeout: FiniteDuration,
 resetTimeout: FiniteDuration,
 maxResetTimeout: FiniteDuration,
 exponentialBackoffFactor: Double,
 randomFactor: Double
)
