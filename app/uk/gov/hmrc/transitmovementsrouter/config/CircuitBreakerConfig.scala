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

package uk.gov.hmrc.transitmovementsrouter.config

import play.api.ConfigLoader
import play.api.Configuration

import scala.concurrent.duration.FiniteDuration

object CircuitBreakerConfig {

  implicit lazy val configLoader: ConfigLoader[CircuitBreakerConfig] = ConfigLoader {
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
