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

package uk.gov.hmrc.transitmovementsrouter.controllers

import akka.stream.scaladsl.Source
import akka.util.ByteString
import cats.data.EitherT
import org.mockito.ArgumentMatchers.any
import org.mockito.MockitoSugar.reset
import org.mockito.MockitoSugar.when
import org.scalacheck.Gen
import org.scalatest.BeforeAndAfterEach
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers.convertToAnyMustWrapper
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar.mock
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import play.api.http.DefaultHttpErrorHandler
import play.api.http.HttpErrorConfig
import play.api.http.Status._
import play.api.libs.Files.SingletonTemporaryFileCreator
import play.api.libs.json.JsString
import play.api.libs.json.JsValue
import play.api.libs.json.Json
import play.api.mvc.PlayBodyParsers
import play.api.mvc.Request
import play.api.test.FakeHeaders
import play.api.test.FakeRequest
import play.api.test.Helpers.contentAsJson
import play.api.test.Helpers.defaultAwaitTimeout
import play.api.test.Helpers.header
import play.api.test.Helpers.status
import play.api.test.Helpers.stubControllerComponents
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.HttpVerbs.POST
import uk.gov.hmrc.transitmovementsrouter.base.StreamTestHelpers.createStream
import uk.gov.hmrc.transitmovementsrouter.base.TestActorSystem
import uk.gov.hmrc.transitmovementsrouter.connectors.PersistenceConnector
import uk.gov.hmrc.transitmovementsrouter.connectors.PushNotificationsConnector
import uk.gov.hmrc.transitmovementsrouter.controllers.actions.MessageSizeActionProvider
import uk.gov.hmrc.transitmovementsrouter.fakes.actions.FakeMessageSizeAction
import uk.gov.hmrc.transitmovementsrouter.fakes.actions.FakeXmlTrimmer
import uk.gov.hmrc.transitmovementsrouter.generators.ModelGenerators
import uk.gov.hmrc.transitmovementsrouter.models.MessageType.RequestOfRelease
import uk.gov.hmrc.transitmovementsrouter.models.EoriNumber
import uk.gov.hmrc.transitmovementsrouter.models.MessageId
import uk.gov.hmrc.transitmovementsrouter.models.MessageType
import uk.gov.hmrc.transitmovementsrouter.models.MovementId
import uk.gov.hmrc.transitmovementsrouter.models.MovementType
import uk.gov.hmrc.transitmovementsrouter.models.PersistenceResponse
import uk.gov.hmrc.transitmovementsrouter.models.RequestMessageType
import uk.gov.hmrc.transitmovementsrouter.models.errors.PersistenceError.MovementNotFound
import uk.gov.hmrc.transitmovementsrouter.models.errors.PersistenceError.Unexpected
import uk.gov.hmrc.transitmovementsrouter.services.RoutingService
import uk.gov.hmrc.transitmovementsrouter.services.StreamingMessageTrimmer
import uk.gov.hmrc.transitmovementsrouter.services.UpscanService
import uk.gov.hmrc.transitmovementsrouter.services.error.RoutingError
import uk.gov.hmrc.transitmovementsrouter.models.CustomsOffice

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.xml.NodeSeq

class MessageControllerSpec
    extends AnyFreeSpec
    with Matchers
    with TestActorSystem
    with BeforeAndAfterEach
    with ScalaCheckDrivenPropertyChecks
    with ModelGenerators {

  val eori         = EoriNumber("eori")
  val movementType = MovementType("departures")
  val movementId   = MovementId("ABC123")
  val messageId    = MessageId("XYZ456")

  val cc015cOfficeOfDepartureGB: NodeSeq =
    <ncts:CC015C PhaseID="NCTS5.0" xmlns:ncts="http://ncts.dgtaxud.ec">
      <preparationDateAndTime>2022-05-25T09:37:04</preparationDateAndTime>
      <CustomsOfficeOfDeparture>
        <referenceNumber>GB1234567</referenceNumber>
      </CustomsOfficeOfDeparture>
    </ncts:CC015C>

  val incomingXml: NodeSeq =
    <TraderChannelResponse><ncts:CC013C PhaseID="NCTS5.0" xmlns:ncts="http://ncts.dgtaxud.ec">text</ncts:CC013C></TraderChannelResponse>
  val trimmedXml: NodeSeq = <ncts:CC013C PhaseID="NCTS5.0" xmlns:ncts="http://ncts.dgtaxud.ec">text</ncts:CC013C>

  val mockRoutingService             = mock[RoutingService]
  val mockPersistenceConnector       = mock[PersistenceConnector]
  val mockPushNotificationsConnector = mock[PushNotificationsConnector]
  val mockUpscanService              = mock[UpscanService]
  implicit val temporaryFileCreator  = SingletonTemporaryFileCreator

  val mockProvider = mock[MessageSizeActionProvider]
  when(mockProvider.apply()).thenReturn(new FakeMessageSizeAction[Nothing])

  val errorHandler                    = new DefaultHttpErrorHandler(HttpErrorConfig(showDevErrors = false, None), None, None)
  val controllerComponentWithTempFile = stubControllerComponents(playBodyParsers = PlayBodyParsers(SingletonTemporaryFileCreator, errorHandler)(materializer))

  def controller(trimmer: StreamingMessageTrimmer = new FakeXmlTrimmer(trimmedXml)) =
    new MessagesController(
      controllerComponentWithTempFile,
      mockRoutingService,
      mockPersistenceConnector,
      mockPushNotificationsConnector,
      mockUpscanService,
      trimmer,
      mockProvider
    )

  def source = createStream(cc015cOfficeOfDepartureGB)

  val outgoing = routes.MessagesController.outgoing(eori, movementType, movementId, messageId).url
  val incoming = routes.MessagesController.incoming((movementId, messageId)).url

  def fakeRequest[A](
    body: NodeSeq,
    url: String,
    headers: FakeHeaders = FakeHeaders(Seq.empty)
  ): Request[Source[ByteString, _]] =
    FakeRequest(
      method = POST,
      uri = url,
      headers = headers,
      body = createStream(body)
    )

  override def afterEach(): Unit = {
    reset(mockRoutingService)
    super.afterEach()
  }

  lazy val submitDeclarationEither: EitherT[Future, RoutingError, Unit] =
    EitherT.rightT(())

  lazy val messageTypeHeader = FakeHeaders(Seq(("X-Message-Type", MessageType.DeclarationData.code)))

  "POST outgoing" - {
    "must return ACCEPTED when declaration is submitted successfully" in {

      when(
        mockRoutingService.submitMessage(
          any[String].asInstanceOf[MovementType],
          any[String].asInstanceOf[MovementId],
          any[String].asInstanceOf[MessageId],
          any[RequestMessageType],
          any[Source[ByteString, _]]
        )(any[HeaderCarrier], any[ExecutionContext])
      ).thenReturn(submitDeclarationEither)

      when(
        mockPushNotificationsConnector
          .post(any[String].asInstanceOf[MovementId], any[String].asInstanceOf[MessageId], any[Source[ByteString, _]])(
            any[HeaderCarrier],
            any[ExecutionContext]
          )
      ).thenReturn(EitherT.rightT(()))

      val result = controller().outgoing(eori, movementType, movementId, messageId)(fakeRequest(cc015cOfficeOfDepartureGB, outgoing, messageTypeHeader))

      status(result) mustBe ACCEPTED
    }

    "must return INVALID_OFFICE when the routing cannot determine where to send a message to" - {

      "returns message to indicate invalid office" in forAll(Gen.alphaNumStr, Gen.alphaStr) {
        (office, field) =>
          when(
            mockRoutingService.submitMessage(
              any[String].asInstanceOf[MovementType],
              any[String].asInstanceOf[MovementId],
              any[String].asInstanceOf[MessageId],
              any[RequestMessageType],
              any[Source[ByteString, _]]
            )(any[HeaderCarrier], any[ExecutionContext])
          ).thenReturn(EitherT[Future, RoutingError, Unit](Future.successful(Left(RoutingError.UnrecognisedOffice("office", CustomsOffice(office), field)))))

          val result = controller().outgoing(eori, movementType, movementId, messageId)(fakeRequest(cc015cOfficeOfDepartureGB, outgoing, messageTypeHeader))

          status(result) mustBe BAD_REQUEST
          contentAsJson(result) mustBe Json.obj(
            "code"    -> "INVALID_OFFICE",
            "message" -> "office",
            "office"  -> office,
            "field"   -> field
          )
      }

    }

    "must return BAD_REQUEST when declaration submission fails" - {

      "returns message to indicate element not found" in {

        when(
          mockRoutingService.submitMessage(
            any[String].asInstanceOf[MovementType],
            any[String].asInstanceOf[MovementId],
            any[String].asInstanceOf[MessageId],
            any[RequestMessageType],
            any[Source[ByteString, _]]
          )(any[HeaderCarrier], any[ExecutionContext])
        ).thenReturn(EitherT[Future, RoutingError, Unit](Future.successful(Left(RoutingError.NoElementFound("messageSender")))))

        val result = controller().outgoing(eori, movementType, movementId, messageId)(fakeRequest(cc015cOfficeOfDepartureGB, outgoing, messageTypeHeader))

        status(result) mustBe BAD_REQUEST
        contentAsJson(result) mustBe Json.obj(
          "code"    -> "BAD_REQUEST",
          "message" -> "Element messageSender not found"
        )
      }

      "returns message to indicate too many elements" in {

        when(
          mockRoutingService.submitMessage(
            any[String].asInstanceOf[MovementType],
            any[String].asInstanceOf[MovementId],
            any[String].asInstanceOf[MessageId],
            any[RequestMessageType],
            any[Source[ByteString, _]]
          )(any[HeaderCarrier], any[ExecutionContext])
        ).thenReturn(EitherT[Future, RoutingError, Unit](Future.successful(Left(RoutingError.TooManyElementsFound("eori")))))

        val result = controller().outgoing(eori, movementType, movementId, messageId)(fakeRequest(cc015cOfficeOfDepartureGB, outgoing, messageTypeHeader))

        status(result) mustBe BAD_REQUEST
        contentAsJson(result) mustBe Json.obj(
          "code"    -> "BAD_REQUEST",
          "message" -> "Found too many elements of type eori"
        )
      }

      "returns message to inform that the X-Message-Type header is not present" in {

        when(
          mockRoutingService.submitMessage(
            any[String].asInstanceOf[MovementType],
            any[String].asInstanceOf[MovementId],
            any[String].asInstanceOf[MessageId],
            any[RequestMessageType],
            any[Source[ByteString, _]]
          )(any[HeaderCarrier], any[ExecutionContext])
        ).thenReturn(EitherT[Future, RoutingError, Unit](Future.successful(Left(RoutingError.NoElementFound("messageSender")))))

        val result = controller().outgoing(eori, movementType, movementId, messageId)(fakeRequest(cc015cOfficeOfDepartureGB, outgoing))

        status(result) mustBe BAD_REQUEST
        contentAsJson(result) mustBe Json.obj(
          "code"    -> "BAD_REQUEST",
          "message" -> "Missing header: X-Message-Type"
        )
      }

      "returns message to inform that the X-Message-Type header value is invalid" in {

        when(
          mockRoutingService.submitMessage(
            any[String].asInstanceOf[MovementType],
            any[String].asInstanceOf[MovementId],
            any[String].asInstanceOf[MessageId],
            any[RequestMessageType],
            any[Source[ByteString, _]]
          )(any[HeaderCarrier], any[ExecutionContext])
        ).thenReturn(EitherT[Future, RoutingError, Unit](Future.successful(Left(RoutingError.NoElementFound("messageSender")))))

        val result = controller().outgoing(eori, movementType, movementId, messageId)(
          fakeRequest(cc015cOfficeOfDepartureGB, outgoing, FakeHeaders(Seq(("X-Message-Type", "EEinvalid"))))
        )

        status(result) mustBe BAD_REQUEST
        contentAsJson(result) mustBe Json.obj(
          "code"    -> "BAD_REQUEST",
          "message" -> "Invalid message type: Invalid X-Message-Type header value: EEinvalid"
        )
      }
    }

    "must return INTERNAL_SERVER_ERROR when declaration submission fails due to unexpected error" in {

      when(
        mockRoutingService.submitMessage(
          any[String].asInstanceOf[MovementType],
          any[String].asInstanceOf[MovementId],
          any[String].asInstanceOf[MessageId],
          any[RequestMessageType],
          any[Source[ByteString, _]]
        )(any[HeaderCarrier], any[ExecutionContext])
      ).thenReturn(
        EitherT[Future, RoutingError, Unit](
          Future.successful(Left(RoutingError.Unexpected("unexpected error", Some(new Exception("An unexpected error occurred")))))
        )
      )

      val result = controller().outgoing(eori, movementType, movementId, messageId)(fakeRequest(cc015cOfficeOfDepartureGB, outgoing, messageTypeHeader))

      status(result) mustBe INTERNAL_SERVER_ERROR
      contentAsJson(result) mustBe Json.obj(
        "code"    -> "INTERNAL_SERVER_ERROR",
        "message" -> "Internal server error"
      )
    }

    "must return BAD_REQUEST when a message is not a request message" in {

      lazy val messageTypeHeader = FakeHeaders(Seq(("X-Message-Type", MessageType.Discrepancies.code)))
      val result                 = controller().outgoing(eori, movementType, movementId, messageId)(fakeRequest(cc015cOfficeOfDepartureGB, outgoing, messageTypeHeader))

      status(result) mustBe BAD_REQUEST
      contentAsJson(result) mustBe Json.obj(
        "code"    -> "BAD_REQUEST",
        "message" -> s"${MessageType.Discrepancies.code} is not valid for requests"
      )
    }

  }

  "POST incoming" - {
    "must return CREATED when message is successfully forwarded" in {
      when(mockPersistenceConnector.post(any[String].asInstanceOf[MovementId], any[String].asInstanceOf[MessageId], any(), any())(any(), any()))
        .thenReturn(EitherT.fromEither(Right(PersistenceResponse(MessageId("1")))))

      val request = fakeRequest(incomingXml, incoming)
        .withHeaders(FakeHeaders().add("X-Message-Type" -> RequestOfRelease.code))

      val result = controller().incoming((movementId, messageId))(request)

      status(result) mustBe CREATED
      header("X-Message-Id", result) mustBe Some("1")
    }

    "must return BAD_REQUEST when the X-Message-Type header is missing" in {

      val result = controller().incoming((movementId, messageId))(fakeRequest(incomingXml, incoming))

      status(result) mustBe BAD_REQUEST

    }

    "must return BAD_REQUEST when message type is invalid" in {

      val request = fakeRequest(incomingXml, incoming)
        .withHeaders(FakeHeaders().add("X-Message-Type" -> "abcdef"))

      val result = controller().incoming((movementId, messageId))(request)

      status(result) mustBe BAD_REQUEST
    }

    "must return NOT_FOUND when target movement is invalid or archived" in {

      when(mockPersistenceConnector.post(any[String].asInstanceOf[MovementId], any[String].asInstanceOf[MessageId], any(), any())(any(), any()))
        .thenReturn(EitherT.fromEither(Left(MovementNotFound(MovementId("ABC")))))

      val request = fakeRequest(incomingXml, incoming)
        .withHeaders(FakeHeaders().add("X-Message-Type" -> RequestOfRelease.code))

      val result = controller().incoming((movementId, messageId))(request)

      status(result) mustBe NOT_FOUND
    }

    "must return INTERNAL_SERVER_ERROR when persistence service fails unexpected" in {

      when(mockPersistenceConnector.post(any[String].asInstanceOf[MovementId], any[String].asInstanceOf[MessageId], any(), any())(any(), any()))
        .thenReturn(EitherT.fromEither(Left(Unexpected(None))))

      val request = fakeRequest(incomingXml, incoming)
        .withHeaders(FakeHeaders().add("X-Message-Type" -> RequestOfRelease.code))

      val result = controller().incoming((movementId, messageId))(request)

      status(result) mustBe INTERNAL_SERVER_ERROR
    }

  }

  "POST /movements/:movementId/messages/:messageId" - {

    "should return ok given a successful response from upscan" in forAll(arbitrarySuccessfulSubmission.arbitrary) {
      successfulSubmission =>
        val request = FakeRequest(
          POST,
          routes.MessagesController.incoming(movementId, messageId).url,
          headers = FakeHeaders(),
          Json.toJson(successfulSubmission)
        )

        when(mockUpscanService.parseUpscanResponse(any[JsValue]))
          .thenReturn(Some(successfulSubmission))

        val result = controller().incomingLargeMessage(movementId, messageId)(request)

        status(result) mustBe OK
    }

    "should return ok given a failure response from upscan" in forAll(arbitrarySubmissionFailure.arbitrary) {
      submissionFailure =>
        val request = FakeRequest(
          POST,
          routes.MessagesController.incoming(movementId, messageId).url,
          headers = FakeHeaders(),
          Json.toJson(submissionFailure)
        )

        when(mockUpscanService.parseUpscanResponse(any[JsValue])).thenReturn(None)

        val result = controller().incomingLargeMessage(movementId, messageId)(request)

        status(result) mustBe OK
    }
  }
}
