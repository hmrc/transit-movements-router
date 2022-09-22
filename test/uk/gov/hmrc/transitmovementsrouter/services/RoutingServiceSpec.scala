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

  "Submitting a declaration request" - new Setup {

    MessageType.departureRequestValues.foreach {
      messageType =>
        s"${messageType.code} should generate a valid departure office and updated payload for a GB payload" in {
          val serviceUnderTest = new RoutingServiceImpl(mockMessageConnectorProvider)
          val payload          = createStream(messageOfficeOfDepartureGB(messageType.rootNode))
          val response = serviceUnderTest.submitMessage(
            MovementType("Departure"),
            MovementId("movement-001"),
            MessageId("message-id-001"),
            messageType,
            payload
          )(hc)

          whenReady(response.value, Timeout(2 seconds)) {
            _.mustBe(Right(()))
          }
        }

        s"${messageType.code} should generate a valid departure office and updated payload for a Xi payload" in {
          val serviceUnderTest = new RoutingServiceImpl(mockMessageConnectorProvider)
          val payload          = createStream(messageOfficeOfDepartureXi(messageType.rootNode))
          val response = serviceUnderTest.submitMessage(
            MovementType("Departure"),
            MovementId("movement-001"),
            MessageId("message-id-001"),
            messageType,
            payload
          )(hc)

          whenReady(response.value, Timeout(2 seconds)) {
            _.mustBe(Right(()))
          }
        }

        s"${messageType.code} returns NoElementFound(referenceNumber) when it does not find an office of departure element" in {
          val serviceUnderTest = new RoutingServiceImpl(mockMessageConnectorProvider)
          val payload          = createStream(emptyMessage(messageType.rootNode))
          val response = serviceUnderTest.submitMessage(
            MovementType("Departure"),
            MovementId("movement-001"),
            MessageId("message-id-001"),
            messageType,
            payload
          )(hc)

          whenReady(response.value, Timeout(2 seconds)) {
            _.mustBe(Left(NoElementFound("referenceNumber")))
          }
        }
    }
  }

  trait Setup {

    val mockMessageConnectorProvider = mock[EISConnectorProvider]
    val mockMessageConnector         = mock[EISConnector]

    when(mockMessageConnectorProvider.gb) thenReturn mockMessageConnector
    when(mockMessageConnectorProvider.xi) thenReturn mockMessageConnector
    when(
      mockMessageConnector.post(
        ArgumentMatchers.any[String].asInstanceOf[MessageSender],
        ArgumentMatchers.any[Source[ByteString, _]],
        ArgumentMatchers.any[HeaderCarrier]
      )
    )
      .thenReturn(Future.successful(Right(())))

    val hc = HeaderCarrier()

    def messageOfficeOfDepartureGB(messageTypeNode: String): NodeSeq = {
      val strMessage =
        s"<$messageTypeNode><preparationDateAndTime>2022-05-25T09:37:04</preparationDateAndTime><CustomsOfficeOfDeparture><referenceNumber>GB1234567</referenceNumber></CustomsOfficeOfDeparture></$messageTypeNode>"
      xml.XML.loadString(strMessage)
    }

    def messageOfficeOfDepartureXi(messageTypeNode: String): NodeSeq = {
      val strMessage =
        s"<$messageTypeNode><preparationDateAndTime>2022-05-25T09:37:04</preparationDateAndTime><CustomsOfficeOfDeparture><referenceNumber>Xi1234567</referenceNumber></CustomsOfficeOfDeparture></$messageTypeNode>"
      xml.XML.loadString(strMessage)
    }

    def emptyMessage(messageTypeNode: String): NodeSeq = {
      val strMessage = s"<$messageTypeNode/>"
      xml.XML.loadString(strMessage)
    }
  }
}
