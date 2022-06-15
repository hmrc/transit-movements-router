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

package uk.gov.hmrc.transitmovementsrouter.services

import akka.stream.Materializer
import akka.stream.scaladsl.Sink
import akka.stream.testkit.NoMaterializer
import akka.util.ByteString
import org.junit.experimental.theories.Theory
import org.scalatest.concurrent.PatienceConfiguration.Timeout
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.mockito.MockitoSugar
import org.mockito.Mockito.when
import org.mockito.Matchers._
import play.api.http.Status
import play.api.libs.ws.ahc.AhcWSClient
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.HttpException
import uk.gov.hmrc.transitmovementsrouter.base.StreamTestHelpers.createStream
import uk.gov.hmrc.transitmovementsrouter.base.TestActorSystem
import uk.gov.hmrc.transitmovementsrouter.base.TestHelpers
import uk.gov.hmrc.transitmovementsrouter.config.CircuitBreakerConfig
import uk.gov.hmrc.transitmovementsrouter.config.EISInstanceConfig
import uk.gov.hmrc.transitmovementsrouter.config.Headers
import uk.gov.hmrc.transitmovementsrouter.config.RetryConfig
import uk.gov.hmrc.transitmovementsrouter.connectors.MessageConnector
import uk.gov.hmrc.transitmovementsrouter.connectors.MessageConnectorImpl
import uk.gov.hmrc.transitmovementsrouter.connectors.MessageConnectorProvider
import uk.gov.hmrc.transitmovementsrouter.connectors.Retries
import uk.gov.hmrc.transitmovementsrouter.connectors.RetriesImpl
import uk.gov.hmrc.transitmovementsrouter.models._
import uk.gov.hmrc.transitmovementsrouter.services.ParseError.NoElementFound
import uk.gov.hmrc.transitmovementsrouter.services.ParseError.Unknown

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.xml.NodeSeq

class RoutingServiceSpec extends AnyFreeSpec with ScalaFutures with TestActorSystem with Matchers with MockitoSugar {

  "Submitting a declaration" - new Setup {

    "should generate a valid departure office and updated payload for a GB payload" in {
      val serviceUnderTest = new RoutingServiceImpl(mockConnector)
      val payload          = createStream(cc015cOfficeOfDepartureGB)
      val response         = serviceUnderTest.submitDeclaration(MovementType("Departure"), MovementId("movement-001"), MessageId("message-id-001"), payload)(hc)

      whenReady(response, Timeout(2 seconds)) {
        _.mustBe(Right(Status.ACCEPTED))
      }
    }

    "should not find an office of departure element" in {
      val serviceUnderTest = new RoutingServiceImpl(mockConnector)
      val payload          = createStream(cc015cEmpty)
      val response         = serviceUnderTest.submitDeclaration(MovementType("Departure"), MovementId("movement-001"), MessageId("message-id-001"), payload)(hc)

      whenReady(response, Timeout(2 seconds)) {
        _.mustBe(Left(NoElementFound("referenceNumber")))
      }
    }

    "should generate a valid departure office and updated payload for a Xi payload" in {
      val serviceUnderTest = new RoutingServiceImpl(mockConnector)
      val payload          = createStream(cc015cOfficeOfDepartureXi)
      val response =
        serviceUnderTest.submitDeclaration(MovementType("Departure"), MovementId("movement-001"), MessageId("message-id-001"), payload)(hc)

      whenReady(response, Timeout(2 seconds)) {
        _.mustBe(Right(Status.ACCEPTED))
      }
    }

  }

  trait Setup {

    val hc = HeaderCarrier()

    val eisInstance = new EISInstanceConfig(
      "http",
      "localhost",
      1234,
      "/gb",
      Headers("bearertokengb"),
      CircuitBreakerConfig(1, 1.second, 1.second, 1.second, 1, 0),
      RetryConfig(1, 1.second, 1.second)
    )

    val materializer: Materializer = Materializer(TestActorSystem.system)
    val ec                         = concurrent.ExecutionContext.Implicits.global
    val mockConnector              = mock[MessageConnectorProvider]
    when(mockConnector.gb) thenReturn (new MessageConnectorImpl("GB", eisInstance, TestHelpers.headerCarrierConfig, AhcWSClient(), new RetriesImpl())(
      ec,
      materializer
    ))
    when(mockConnector.xi) thenReturn (new MessageConnectorImpl("Xi", eisInstance, TestHelpers.headerCarrierConfig, AhcWSClient(), new RetriesImpl())(
      ec,
      materializer
    ))

    val cc015cOfficeOfDepartureGB: NodeSeq =
      <CC015C>
        <preparationDateAndTime>2022-05-25T09:37:04</preparationDateAndTime>
        <CustomsOfficeOfDeparture>
          <referenceNumber>GB1234567</referenceNumber>
        </CustomsOfficeOfDeparture>
      </CC015C>

    val cc015cOfficeOfDepartureXi: NodeSeq =
      <CC015C>
        <preparationDateAndTime>2022-05-25T09:37:04</preparationDateAndTime>
        <CustomsOfficeOfDeparture>
          <referenceNumber>Xi1234567</referenceNumber>
        </CustomsOfficeOfDeparture>
      </CC015C>

    val cc015cEmpty: NodeSeq = <CC015C/>

  }
}
