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

import akka.stream.scaladsl.Source
import akka.util.ByteString
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.when
import org.scalatest.concurrent.PatienceConfiguration.Timeout
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.mockito.MockitoSugar
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.transitmovementsrouter.base.StreamTestHelpers.createStream
import uk.gov.hmrc.transitmovementsrouter.base._
import uk.gov.hmrc.transitmovementsrouter.connectors._
import uk.gov.hmrc.transitmovementsrouter.models._
import uk.gov.hmrc.transitmovementsrouter.services.error.RoutingError.NoElementFound

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.xml.NodeSeq

class RoutingServiceSpec extends AnyFreeSpec with ScalaFutures with TestActorSystem with Matchers with MockitoSugar {

  "Submitting a declaration" - new Setup {

    "should generate a valid departure office and updated payload for a GB payload" in {
      val serviceUnderTest = new RoutingServiceImpl(mockMessageConnectorProvider)
      val payload          = createStream(cc015cOfficeOfDepartureGB)
      val response         = serviceUnderTest.submitDeclaration(MovementType("Departure"), MovementId("movement-001"), MessageId("message-id-001"), payload)(hc)

      whenReady(response.value, Timeout(2 seconds)) {
        _.mustBe(Right(Unit))
      }
    }

    "should not find an office of departure element" in {
      val serviceUnderTest = new RoutingServiceImpl(mockMessageConnectorProvider)
      val payload          = createStream(cc015cEmpty)
      val response         = serviceUnderTest.submitDeclaration(MovementType("Departure"), MovementId("movement-001"), MessageId("message-id-001"), payload)(hc)

      whenReady(response.value, Timeout(2 seconds)) {
        _.mustBe(Left(NoElementFound("referenceNumber")))
      }
    }

    "should generate a valid departure office and updated payload for a Xi payload" in {
      val serviceUnderTest = new RoutingServiceImpl(mockMessageConnectorProvider)
      val payload          = createStream(cc015cOfficeOfDepartureXi)
      val response =
        serviceUnderTest.submitDeclaration(MovementType("Departure"), MovementId("movement-001"), MessageId("message-id-001"), payload)(hc)

      whenReady(response.value, Timeout(2 seconds)) {
        _.mustBe(Right(Unit))
      }
    }

  }

  trait Setup {

    val mockMessageConnectorProvider = mock[MessageConnectorProvider]
    val mockMessageConnector         = mock[MessageConnector]

    when(mockMessageConnectorProvider.gb) thenReturn mockMessageConnector
    when(mockMessageConnectorProvider.xi) thenReturn mockMessageConnector
    when(mockMessageConnector.post(ArgumentMatchers.any[MessageSender], ArgumentMatchers.any[Source[ByteString, _]], ArgumentMatchers.any[HeaderCarrier]))
      .thenReturn(Future.successful(Right(())))

    val hc = HeaderCarrier()

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
