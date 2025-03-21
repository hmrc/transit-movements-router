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

import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.testkit.NoMaterializer
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
import uk.gov.hmrc.http.test.HttpClientV2Support
import uk.gov.hmrc.transitmovementsrouter.config.AppConfig
import uk.gov.hmrc.transitmovementsrouter.config.CircuitBreakerConfig
import uk.gov.hmrc.transitmovementsrouter.config.EISInstanceConfig
import uk.gov.hmrc.transitmovementsrouter.config.Headers
import uk.gov.hmrc.transitmovementsrouter.config.RetryConfig

import java.time.Clock
import scala.concurrent.ExecutionContext.Implicits.global

class EISConnectorProviderSpec extends AnyFreeSpec with HttpClientV2Support with Matchers with ScalaFutures with MockitoSugar with BeforeAndAfterEach {

  implicit val materializer: Materializer = NoMaterializer

  val appConfig: AppConfig = mock[AppConfig]
  val retries              = new RetriesImpl()

  override def beforeEach(): Unit = {
    when(appConfig.logBodyOnEIS500).thenReturn(true)
    when(appConfig.eisGb).thenReturn(
      new EISInstanceConfig(
        "http",
        "localhost",
        1234,
        "/gb",
        Headers("bearertokengb"),
        CircuitBreakerConfig(1, 1.second, 1.second, 1.second, 1, 0),
        RetryConfig(1, 1.second, 1.second),
        true
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
        RetryConfig(1, 1.second, 1.second),
        true
      )
    )
  }

  override def afterEach(): Unit =
    reset(appConfig)

  "When creating the provider" - {

    "getting the GB v2.1 connector will get the GB v2.1 connector" in {
      val sut = new EISConnectorProviderImpl(appConfig, retries, httpClientV2, Clock.systemUTC())
      sut.gbV2_1

      verify(appConfig, times(1)).eisGbV2_1
      verify(appConfig, times(0)).eisGb
    }

    "getting the GB v2.0 connector will get the GB v2.1 connect if transitionalToSit2 is 'true'" in {
      when(appConfig.transitionalToSit2).thenReturn(true)
      val sut = new EISConnectorProviderImpl(appConfig, retries, httpClientV2, Clock.systemUTC())

      sut.gb

      verify(appConfig, times(1)).eisGbV2_1
      verify(appConfig, times(0)).eisGb
    }

    "getting the XI v2.0 connector will get the XI v2.1 connector if transitionalToSit2 is 'true'" in {
      when(appConfig.transitionalToSit2).thenReturn(true)
      val sut = new EISConnectorProviderImpl(appConfig, retries, httpClientV2, Clock.systemUTC())

      sut.xi

      verify(appConfig, times(1)).eisXiV2_1
      verify(appConfig, times(0)).eisXi
    }

    "getting the GB v2.1 connector will get the GB v2.0 connect if forceTransitionalInflight is 'true'" in {
      when(appConfig.finalToSit1).thenReturn(true)
      val sut = new EISConnectorProviderImpl(appConfig, retries, httpClientV2, Clock.systemUTC())

      sut.gbV2_1

      verify(appConfig, times(1)).eisGb
      verify(appConfig, times(0)).eisGbV2_1
    }

    "getting the XI v2.1 connector will get the XI v2.0 connector if finalToSit1 is 'true'" in {
      when(appConfig.finalToSit1).thenReturn(true)
      val sut = new EISConnectorProviderImpl(appConfig, retries, httpClientV2, Clock.systemUTC())

      sut.xiV2_1

      verify(appConfig, times(1)).eisXi
      verify(appConfig, times(0)).eisXiV2_1
    }

    "getting the XI v2.1 connector will get the XI v2.1 connector" in {
      val sut = new EISConnectorProviderImpl(appConfig, retries, httpClientV2, Clock.systemUTC())
      sut.xiV2_1

      verify(appConfig, times(1)).eisXiV2_1
      verify(appConfig, times(0)).eisGb
    }

    "getting the GB connector will get the GB config" in {

      // Given this message connector
      val sut = new EISConnectorProviderImpl(appConfig, retries, httpClientV2, Clock.systemUTC())

      // When we call the lazy val for GB
      sut.gb

      // Then the appConfig eisGb should have been called
      verify(appConfig, times(1)).eisGb

      // and that appConfig eisXi was not
      verify(appConfig, times(0)).eisXi
    }

    "getting the XI connector will get the XI config" in {

      // Given this message connector
      val sut = new EISConnectorProviderImpl(appConfig, retries, httpClientV2, Clock.systemUTC())

      // When we call the lazy val for XI
      sut.xi

      // Then the appConfig eisXi should have been called
      verify(appConfig, times(1)).eisXi

      // and that appConfig eisGb was not
      verify(appConfig, times(0)).eisGb
    }

    "both connectors are not the same" in {

      // Given this message connector
      val sut = new EISConnectorProviderImpl(appConfig, retries, httpClientV2, Clock.systemUTC())

      sut.gb must not be sut.xi
    }

  }

}
