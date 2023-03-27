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

package uk.gov.hmrc.transitmovementsrouter.services

import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.Source
import akka.util.ByteString
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
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
import play.api.http.Status.INTERNAL_SERVER_ERROR
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.UpstreamErrorResponse
import uk.gov.hmrc.transitmovementsrouter.base.StreamTestHelpers.createStream
import uk.gov.hmrc.transitmovementsrouter.base._
import uk.gov.hmrc.transitmovementsrouter.connectors._
import uk.gov.hmrc.transitmovementsrouter.generators.TestModelGenerators
import uk.gov.hmrc.transitmovementsrouter.models._

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
    with TestModelGenerators
    with OptionValues {

  implicit val hc: HeaderCarrier = HeaderCarrier()

  val mockMessageConnectorProvider = mock[EISConnectorProvider]
  val mockMessageConnector         = mock[EISConnector]

  "Submitting a payload" - {

    "given a valid response from connector" - {
      when(mockMessageConnectorProvider.gb) thenReturn mockMessageConnector
      when(mockMessageConnectorProvider.xi) thenReturn mockMessageConnector

      MessageType.departureRequestValues.foreach {
        messageType =>
          s"${messageType.code} should return valid response for a GB payload" in forAll(
            arbitrary[MessageId],
            arbitrary[MovementId],
            messageWithDepartureOfficeNode(messageType, "GB")
          ) {
            (messageId, movementId, officeOfDepartureXML) =>
              when(
                mockMessageConnector.post(
                  MovementId(anyString()),
                  MessageId(anyString()),
                  any[Source[ByteString, _]],
                  any[HeaderCarrier]
                )
              )
                .thenReturn(Future.successful(Right(())))
              val mockEISMessageTransformer = mock[EISMessageTransformers]
              when(mockEISMessageTransformer.wrap).thenAnswer(
                _ => Flow[ByteString]
              )

              val serviceUnderTest = new RoutingServiceImpl(mockEISMessageTransformer, mockMessageConnectorProvider)
              val payload          = createStream(officeOfDepartureXML._1)
              val response = serviceUnderTest.submitMessage(
                MovementType("departures"),
                movementId,
                messageId,
                payload,
                CustomsOffice(officeOfDepartureXML._2)
              )

              whenReady(response.value, Timeout(2.seconds)) {
                r =>
                  r.mustBe(Right(()))
                  verify(mockEISMessageTransformer, times(1)).wrap
              }
          }

          s"${messageType.code} should return valid response for a Xi payload" in forAll(
            arbitrary[MessageId],
            arbitrary[MovementId],
            messageWithDepartureOfficeNode(messageType, "XI")
          ) {
            (messageId, movementId, officeOfDepartureXML) =>
              val mockEISMessageTransformer = mock[EISMessageTransformers]
              when(mockEISMessageTransformer.wrap).thenAnswer(
                _ => Flow[ByteString]
              )

              val serviceUnderTest = new RoutingServiceImpl(mockEISMessageTransformer, mockMessageConnectorProvider)
              val payload          = createStream(officeOfDepartureXML._1)
              val response = serviceUnderTest.submitMessage(
                MovementType("departures"),
                movementId,
                messageId,
                payload,
                CustomsOffice(officeOfDepartureXML._2)
              )

              whenReady(response.value, Timeout(2.seconds)) {
                r =>
                  r.mustBe(Right(()))
                  verify(mockEISMessageTransformer, times(1)).wrap
              }
          }

      }
    }

    "given an error from connector" - {

      when(mockMessageConnectorProvider.gb) thenReturn mockMessageConnector
      when(mockMessageConnectorProvider.xi) thenReturn mockMessageConnector

      s"should return error response for a GB payload" in forAll(
        arbitrary[MessageId],
        arbitrary[MovementId],
        messageWithDepartureOfficeNode(MessageType.DeclarationData, "GB")
      ) {
        (messageId, movementId, officeOfDepartureXML) =>
          val upstreamErrorResponse: Throwable = UpstreamErrorResponse("Internal service error", INTERNAL_SERVER_ERROR)
          when(
            mockMessageConnector.post(
              MovementId(anyString()),
              MessageId(anyString()),
              any[Source[ByteString, _]],
              any[HeaderCarrier]
            )
          )
            .thenReturn(Future.failed(upstreamErrorResponse))
          val mockEISMessageTransformer = mock[EISMessageTransformers]
          when(mockEISMessageTransformer.wrap).thenAnswer(
            _ => Flow[ByteString]
          )

          val serviceUnderTest = new RoutingServiceImpl(mockEISMessageTransformer, mockMessageConnectorProvider)
          val payload          = createStream(officeOfDepartureXML._1)
          val response = serviceUnderTest.submitMessage(
            MovementType("departures"),
            movementId,
            messageId,
            payload,
            CustomsOffice(officeOfDepartureXML._2)
          )

          whenReady(response.value.failed, Timeout(2.seconds)) {
            r => r.mustBe(upstreamErrorResponse)

          }
      }
    }

    s"should return error response for a XI payload" in forAll(
      arbitrary[MessageId],
      arbitrary[MovementId],
      messageWithDepartureOfficeNode(MessageType.DeclarationData, "XI")
    ) {
      (messageId, movementId, officeOfDepartureXML) =>
        val upstreamErrorResponse: Throwable = UpstreamErrorResponse("Internal service error", INTERNAL_SERVER_ERROR)
        when(
          mockMessageConnector.post(
            MovementId(anyString()),
            MessageId(anyString()),
            any[Source[ByteString, _]],
            any[HeaderCarrier]
          )
        )
          .thenReturn(Future.failed(upstreamErrorResponse))
        val mockEISMessageTransformer = mock[EISMessageTransformers]
        when(mockEISMessageTransformer.wrap).thenAnswer(
          _ => Flow[ByteString]
        )

        val serviceUnderTest = new RoutingServiceImpl(mockEISMessageTransformer, mockMessageConnectorProvider)
        val payload          = createStream(officeOfDepartureXML._1)
        val response = serviceUnderTest.submitMessage(
          MovementType("departures"),
          movementId,
          messageId,
          payload,
          CustomsOffice(officeOfDepartureXML._2)
        )

        whenReady(response.value.failed, Timeout(2.seconds)) {
          r => r.mustBe(upstreamErrorResponse)

        }
    }

  }

  def createReferenceNumberWithPrefix(prefix: String): Gen[String] = intWithMaxLength(7, 7).map(
    n => s"$prefix$n"
  )

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
