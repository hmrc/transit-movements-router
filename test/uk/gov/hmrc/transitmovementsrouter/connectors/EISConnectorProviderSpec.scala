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

  override def beforeEach(): Unit =
    when(appConfig.logBodyOnEIS500).thenReturn(true)

  override def afterEach(): Unit =
    reset(appConfig)

  "When creating the provider" - {

    "getting the GB v2.1 connector will get the GB v2.1 connector" in {
      val sut = new EISConnectorProviderImpl(appConfig, retries, httpClientV2, Clock.systemUTC())
      sut.gbV2_1

      verify(appConfig, times(1)).eisGbV2_1
    }

    "getting the XI v2.1 connector will get the XI v2.1 connector" in {
      val sut = new EISConnectorProviderImpl(appConfig, retries, httpClientV2, Clock.systemUTC())
      sut.xiV2_1

      verify(appConfig, times(1)).eisXiV2_1
    }

    "both connectors are not the same" in {

      // Given this message connector
      val sut = new EISConnectorProviderImpl(appConfig, retries, httpClientV2, Clock.systemUTC())

      sut.gbV2_1 must not be sut.xiV2_1
    }

  }

}
