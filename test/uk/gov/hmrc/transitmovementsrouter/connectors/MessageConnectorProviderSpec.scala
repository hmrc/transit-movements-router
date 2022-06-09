/*
 * Copyright 2022 HM Revenue & Customs
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

import akka.stream.Materializer
import akka.stream.testkit.NoMaterializer
import org.mockito.Mockito.reset
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.when
import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatest.time.SpanSugar.convertIntToGrainOfTime
import org.scalatestplus.mockito.MockitoSugar
import play.api.libs.ws.ahc.AhcWSClient
import uk.gov.hmrc.transitmovementsrouter.config.AppConfig
import uk.gov.hmrc.transitmovementsrouter.config.CircuitBreakerConfig
import uk.gov.hmrc.transitmovementsrouter.config.EISInstanceConfig
import uk.gov.hmrc.transitmovementsrouter.config.Headers
import uk.gov.hmrc.transitmovementsrouter.config.RetryConfig

import scala.concurrent.ExecutionContext.Implicits.global

class MessageConnectorProviderSpec extends AnyFreeSpec with Matchers with ScalaFutures with MockitoSugar with BeforeAndAfterEach {

  implicit val materializer: Materializer = NoMaterializer

  val appConfig = mock[AppConfig]
  val retries   = new RetriesImpl()


  override def beforeEach(): Unit = {
    when(appConfig.eisGb).thenReturn(
      new EISInstanceConfig(
        "http",
        "localhost",
        1234,
        "/gb",
        Headers("bearertokengb"),
        CircuitBreakerConfig(1, 1.second, 1.second, 1.second, 1, 0),
        RetryConfig(1, 1.second, 1.second)
      )
    )

    when(appConfig.eisXi).thenReturn(
      new EISInstanceConfig(
        "http",
        "localhost",
        1234,
        "/xi",
        Headers("bearertokengb"),
        CircuitBreakerConfig(1, 1.second, 1.second, 1.second, 1, 0),
        RetryConfig(1, 1.second, 1.second)
      )
    )
  }

  override def afterEach(): Unit = {
    reset(appConfig)
  }

  "When creating the provider" - {

    "getting the GB connector will get the GB config" in {

      // Given this message connector
      val sut = new MessageConnectorProviderImpl(appConfig, retries, AhcWSClient())

      // When we call the lazy val for GB
      sut.gb

      // Then the appConfig eisGb should have been called
      verify(appConfig, times(1)).eisGb

      // and that appConfig eisXi was not
      verify(appConfig, times(0)).eisXi
    }

    "getting the XI connector will get the XI config" in {

      // Given this message connector
      val sut = new MessageConnectorProviderImpl(appConfig, retries, AhcWSClient())

      // When we call the lazy val for XI
      sut.xi

      // Then the appConfig eisXi should have been called
      verify(appConfig, times(1)).eisXi

      // and that appConfig eisGb was not
      verify(appConfig, times(0)).eisGb
    }

  }

}
