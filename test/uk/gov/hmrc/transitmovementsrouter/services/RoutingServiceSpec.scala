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
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
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

  implicit val hc: HeaderCarrier = HeaderCarrier()

  "Submitting a payload" - new Setup {

    MessageType.departureRequestValues.foreach {
      messageType =>
        s"${messageType.code} should generate a valid departure office and updated payload for a GB payload" in forAll(
          arbitrary[MessageId],
          arbitrary[MovementId],
          messageWithDepartureOfficeNode(messageType, "GB")
        ) {
          (messageId, movementId, officeOfDepartureXML) =>
            val serviceUnderTest = new RoutingServiceImpl(mockMessageConnectorProvider)
            val payload          = createStream(officeOfDepartureXML._1)
            val response = serviceUnderTest.submitMessage(
              MovementType("departures"),
              movementId,
              messageId,
              messageType,
              payload
            )

            whenReady(response.value, Timeout(2 seconds)) {
              _.mustBe(Right(()))
            }
        }

        s"${messageType.code} should generate a valid departure office and updated payload for a Xi payload" in forAll(
          arbitrary[MessageId],
          arbitrary[MovementId],
          messageWithDepartureOfficeNode(messageType, "XI")
        ) {
          (messageId, movementId, officeOfDepartureXML) =>
            val serviceUnderTest = new RoutingServiceImpl(mockMessageConnectorProvider)
            val payload          = createStream(officeOfDepartureXML._1)
            val response = serviceUnderTest.submitMessage(
              MovementType("departures"),
              movementId,
              messageId,
              messageType,
              payload
            )

            whenReady(response.value, Timeout(2 seconds)) {
              _.mustBe(Right(()))
            }
        }

        s"${messageType.code} should generate an invalid office of destination for a non GB/XI payload" in forAll(
          arbitrary[MessageId],
          arbitrary[MovementId],
          messageWithDepartureOfficeNode(messageType, "FR")
        ) {
          (messageId, movementId, officeOfDeparture) =>
            val serviceUnderTest = new RoutingServiceImpl(mockMessageConnectorProvider)
            val referenceNumber  = officeOfDeparture._2
            val payload          = createStream(officeOfDeparture._1)
            val response = serviceUnderTest.submitMessage(
              MovementType("departures"),
              movementId,
              messageId,
              messageType,
              payload
            )

            whenReady(response.value, Timeout(2 seconds)) {
              _.mustBe(
                Left(RoutingError.UnrecognisedOffice(s"Did not recognise office: $referenceNumber", CustomsOffice(referenceNumber), messageType.officeNode))
              )
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
            )

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
          messageWithDestinationOfficeNode(messageType, "GB")
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
            )

            whenReady(response.value, Timeout(2 seconds)) {
              _.mustBe(Right(()))
            }
        }

        s"${messageType.code} should generate a valid office of destination and updated payload for a XI payload" in forAll(
          arbitrary[MessageId],
          arbitrary[MovementId],
          messageWithDestinationOfficeNode(messageType, "XI")
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
            )

            whenReady(response.value, Timeout(2 seconds)) {
              _.mustBe(Right(()))
            }
        }

        val officeOfDestinationXML = messageWithDestinationOfficeNode(messageType, "FR").sample.value

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
            )

            whenReady(response.value, Timeout(2 seconds)) {
              _.mustBe(
                Left(
                  RoutingError.UnrecognisedOffice(
                    s"Did not recognise office: ${officeOfDestinationXML._2}",
                    CustomsOffice(officeOfDestinationXML._2),
                    messageType.officeNode
                  )
                )
              )
            }
        }

    }
  }

  "selectConnector" - {

    val mockGbEISConnector = mock[EISConnector]
    when(mockGbEISConnector.toString).thenReturn("GB")
    val mockXiEISConnector = mock[EISConnector]
    when(mockXiEISConnector.toString).thenReturn("XI")

    val provider = new EISConnectorProvider {
      override def gb: EISConnector = mockGbEISConnector
      override def xi: EISConnector = mockXiEISConnector
    }

    val sut = new RoutingServiceImpl(provider)

    val requestMessageTypes = Gen.oneOf(MessageType.departureRequestValues ++ MessageType.arrivalRequestValues)

    "GB returns the GB EIS connector" in forAll(createReferenceNumberWithPrefix("GB"), requestMessageTypes) {
      (office, messageType) =>
        sut.selectConnector(Right(CustomsOffice(office)), messageType) mustBe Right(mockGbEISConnector)
    }

    "XI returns the XI EIS connector" in forAll(createReferenceNumberWithPrefix("XI"), requestMessageTypes) {
      (office, messageType) =>
        sut.selectConnector(Right(CustomsOffice(office)), messageType) mustBe Right(mockXiEISConnector)
    }

    "Other offices returns a RoutingError" in forAll(createReferenceNumberWithPrefix("FR"), requestMessageTypes) {
      (office, messageType) =>
        sut.selectConnector(Right(CustomsOffice(office)), messageType) mustBe Left(
          RoutingError.UnrecognisedOffice(s"Did not recognise office: $office", CustomsOffice(office), messageType.officeNode)
        )
    }

    "Pre-existing error must be preserved" in forAll(requestMessageTypes) {
      messageType =>
        val error = RoutingError.Unexpected("error", Some(new IllegalStateException()))
        sut.selectConnector(Left(error), messageType) mustBe Left(error)
    }

  }

  def createReferenceNumberWithPrefix(prefix: String): Gen[String] = intWithMaxLength(7, 7).map(
    n => s"$prefix$n"
  )

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

    def messageWithDepartureOfficeNode(messageType: RequestMessageType, referenceType: String): Gen[(String, String)] =
      for {
        dateTime        <- arbitrary[OffsetDateTime].map(_.toLocalDateTime.truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_LOCAL_DATE))
        referenceNumber <- createReferenceNumberWithPrefix(referenceType)
      } yield (
        s"<${messageType.rootNode}><preparationDateAndTime>$dateTime</preparationDateAndTime><${messageType.officeNode}><referenceNumber>$referenceNumber</referenceNumber></${messageType.officeNode}></${messageType.rootNode}>",
        referenceNumber
      )

    def messageWithDestinationOfficeNode(messageType: RequestMessageType, referenceType: String): Gen[(String, String)] =
      for {
        referenceNumber <- createReferenceNumberWithPrefix(referenceType)
      } yield (
        s"""<${messageType.rootNode}>
                           |<messageType>${messageType.code}</messageType>
                           |<${messageType.officeNode}>
                           |  <referenceNumber>$referenceNumber</referenceNumber>
                           |</${messageType.officeNode}>
                           |</${messageType.rootNode}>""".stripMargin,
        referenceNumber
      )

    def emptyMessage(messageTypeNode: String): String = s"<$messageTypeNode/>"
  }
}
