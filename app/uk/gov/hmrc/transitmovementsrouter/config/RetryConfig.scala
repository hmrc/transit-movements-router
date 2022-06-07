package uk.gov.hmrc.transitmovementsrouter.config

import play.api.ConfigLoader
import play.api.Configuration

import scala.concurrent.duration.FiniteDuration

object RetryConfig {

  implicit val configLoader: ConfigLoader[RetryConfig] = ConfigLoader {
    rootConfig => path =>
      val config = Configuration(rootConfig.getConfig(path))
      RetryConfig(
        config.get[Int]("max-retries"),
        config.get[FiniteDuration]("delay-between-retries"),
        config.get[FiniteDuration]("timeout")
      )
  }

}
case class RetryConfig(maxRetries: Int, delay: FiniteDuration, timeout: FiniteDuration)
