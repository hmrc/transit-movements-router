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
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Gen
import org.scalatest.OptionValues
import org.scalatest.concurrent.PatienceConfiguration.Timeout
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.transitmovementsrouter.base.StreamTestHelpers.createStream
import uk.gov.hmrc.transitmovementsrouter.base._
import uk.gov.hmrc.transitmovementsrouter.connectors._
import uk.gov.hmrc.transitmovementsrouter.generators.ModelGenerators
import uk.gov.hmrc.transitmovementsrouter.models._
import uk.gov.hmrc.transitmovementsrouter.services.error.RoutingError
import uk.gov.hmrc.transitmovementsrouter.services.error.RoutingError.NoElementFound

import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import scala.concurrent.Future
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt

class RoutingServiceSpec
    extends AnyFreeSpec
    with ScalaFutures
    with TestActorSystem
    with Matchers
    with MockitoSugar
    with ScalaCheckDrivenPropertyChecks
    with ModelGenerators
    with OptionValues {

  "Submitting a declaration request" - new Setup {

    MessageType.departureRequestValues.foreach {
      messageType =>
        s"${messageType.code} should generate a valid departure office and updated payload for a GB payload" in forAll(
          arbitrary[MessageId],
          arbitrary[MovementId],
          messageOfficeOfDeparture(messageType.rootNode, "GB")
        ) {
          (messageId, movementId, officeOfDepartureXML) =>
            val serviceUnderTest = new RoutingServiceImpl(mockMessageConnectorProvider)
            val payload          = createStream(officeOfDepartureXML)
            val response = serviceUnderTest.submitMessage(
              MovementType("departures"),
              movementId,
              messageId,
              messageType,
              payload
            )(hc, ec)

            whenReady(response.value, Timeout(2 seconds)) {
              _.mustBe(Right(()))
            }
        }

        s"${messageType.code} should generate a valid departure office and updated payload for a Xi payload" in forAll(
          arbitrary[MessageId],
          arbitrary[MovementId],
          messageOfficeOfDeparture(messageType.rootNode, "XI")
        ) {
          (messageId, movementId, officeOfDepartureXML) =>
            val serviceUnderTest = new RoutingServiceImpl(mockMessageConnectorProvider)
            val payload          = createStream(officeOfDepartureXML)
            val response = serviceUnderTest.submitMessage(
              MovementType("departures"),
              movementId,
              messageId,
              messageType,
              payload
            )(hc, ec)

            whenReady(response.value, Timeout(2 seconds)) {
              _.mustBe(Right(()))
            }
        }

        s"${messageType.code} returns NoElementFound(referenceNumber) when it does not find an office of departure element" in forAll(
          arbitrary[MessageId],
          arbitrary[MovementId]
        ) {
          (messageId, movementId) =>
            val serviceUnderTest = new RoutingServiceImpl(mockMessageConnectorProvider)
            val payload          = createStream(emptyMessage(messageType.rootNode))
            val response = serviceUnderTest.submitMessage(
              MovementType("departures"),
              movementId,
              messageId,
              messageType,
              payload
            )(hc, ec)

            whenReady(response.value, Timeout(2 seconds)) {
              _.mustBe(Left(NoElementFound("referenceNumber")))
            }
        }
    }

    MessageType.arrivalRequestValues.foreach {
      messageType =>
        s"${messageType.code} should generate a valid office of destination and updated payload for a GB payload" in forAll(
          arbitrary[MessageId],
          arbitrary[MovementId],
          messageOfficeOfDestinationActual(messageType.rootNode, "GB")
        ) {
          (messageId, movementId, officeOfDestinationXML) =>
            val serviceUnderTest = new RoutingServiceImpl(mockMessageConnectorProvider)
            val payload          = createStream(officeOfDestinationXML._1)
            val response = serviceUnderTest.submitMessage(
              MovementType("arrivals"),
              movementId,
              messageId,
              messageType,
              payload
            )(hc, ec)

            whenReady(response.value, Timeout(2 seconds)) {
              _.mustBe(Right(()))
            }
        }

        s"${messageType.code} should generate a valid office of destination and updated payload for a XI payload" in forAll(
          arbitrary[MessageId],
          arbitrary[MovementId],
          messageOfficeOfDestinationActual(messageType.rootNode, "XI")
        ) {
          (messageId, movementId, officeOfDestinationXML) =>
            val serviceUnderTest = new RoutingServiceImpl(mockMessageConnectorProvider)
            val payload          = createStream(officeOfDestinationXML._1)
            val response = serviceUnderTest.submitMessage(
              MovementType("arrivals"),
              movementId,
              messageId,
              messageType,
              payload
            )(hc, ec)

            whenReady(response.value, Timeout(2 seconds)) {
              _.mustBe(Right(()))
            }
        }

        val officeOfDestinationXML = messageOfficeOfDestinationActual(messageType.rootNode, "FR").sample.value

        s"${messageType.code} should generate an invalid office of destination for a non GB/XI payload" in forAll(
          arbitrary[MessageId],
          arbitrary[MovementId]
        ) {
          (messageId, movementId) =>
            val serviceUnderTest = new RoutingServiceImpl(mockMessageConnectorProvider)
            val payload          = createStream(officeOfDestinationXML._1)

            val response = serviceUnderTest.submitMessage(
              MovementType("arrivals"),
              movementId,
              messageId,
              messageType,
              payload
            )(hc, ec)

            whenReady(response.value, Timeout(2 seconds)) {
              _.mustBe(
                Left(RoutingError.UnrecognisedOffice(s"Did not recognise office: ${officeOfDestinationXML._2}", CustomsOffice(officeOfDestinationXML._2)))
              )
            }
        }

    }
  }

  trait Setup {

    val hc = HeaderCarrier()
    val ec = ExecutionContext.Implicits.global

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

    def messageOfficeOfDeparture(messageTypeNode: String, referenceType: String): Gen[String] =
      for {
        dateTime <- arbitrary[OffsetDateTime].map(_.toLocalDateTime.truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_LOCAL_DATE))
        referenceNumber <- intWithMaxLength(7, 7).map(
          n => s"$referenceType$n"
        )
      } yield s"<$messageTypeNode><preparationDateAndTime>$dateTime</preparationDateAndTime><CustomsOfficeOfDeparture><referenceNumber>$referenceNumber</referenceNumber></CustomsOfficeOfDeparture></$messageTypeNode>"

    def messageOfficeOfDestinationActual(messageTypeNode: String, referenceType: String): Gen[(String, String)] =
      for {
        dateTime <- arbitrary[OffsetDateTime].map(_.toLocalDateTime.truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_LOCAL_DATE))
        referenceNumber <- intWithMaxLength(7, 7).map(
          n => s"$referenceType$n"
        )
      } yield (
        s"""<$messageTypeNode>
                           |<messageType>$messageTypeNode</messageType>
                           |<CustomsOfficeOfDestinationActual>
                           |  <referenceNumber>$referenceNumber</referenceNumber>
                           |</CustomsOfficeOfDestinationActual>
                           |</$messageTypeNode>""".stripMargin,
        referenceNumber
      )

    def emptyMessage(messageTypeNode: String): String = s"<$messageTypeNode/>"
  }
}
