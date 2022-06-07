package uk.gov.hmrc.transitmovementsrouter.config

import play.api.ConfigLoader
import play.api.Configuration

object EISInstanceConfig {

  implicit val configLoader: ConfigLoader[EISInstanceConfig] =
    ConfigLoader {
      rootConfig => path =>
        val config = Configuration(rootConfig.getConfig(path))
        EISInstanceConfig(
          config.get[String]("protocol"),
          config.get[String]("host"),
          config.get[Int]("port"),
          config.get[String]("uri"),
          config.get[Headers]("headers"),
          config.get[CircuitBreakerConfig]("circuitBreaker"),
          config.get[RetryConfig]("retryConfig")
        )
    }

}
case class EISInstanceConfig(protocol: String, host: String, port: Int, uri: String, headers: Headers, circuitBreaker: CircuitBreakerConfig, retryConfig: RetryConfig) {

  lazy val url: String = s"$protocol://$host:$port$uri"

}