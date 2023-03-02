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
import play.api.http.HeaderNames
import play.api.http.HttpErrorConfig
import play.api.http.MimeTypes
import play.api.http.Status._
import play.api.libs.Files.SingletonTemporaryFileCreator
import play.api.libs.json.JsValue
import play.api.libs.json.Json
import play.api.mvc._
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
import uk.gov.hmrc.transitmovementsrouter.controllers.actions.AuthenticateEISToken
import uk.gov.hmrc.transitmovementsrouter.controllers.errors.PresentationError
import uk.gov.hmrc.transitmovementsrouter.fakes.actions.FakeXmlTransformer
import uk.gov.hmrc.transitmovementsrouter.generators.TestModelGenerators
import uk.gov.hmrc.transitmovementsrouter.models.MessageType.RequestOfRelease
import uk.gov.hmrc.transitmovementsrouter.models._
import uk.gov.hmrc.transitmovementsrouter.models.errors.PersistenceError.MovementNotFound
import uk.gov.hmrc.transitmovementsrouter.models.errors.PersistenceError.Unexpected
import uk.gov.hmrc.transitmovementsrouter.models.errors.MessageTypeExtractionError
import uk.gov.hmrc.transitmovementsrouter.models.errors.ObjectStoreError
import uk.gov.hmrc.transitmovementsrouter.models.responses.UpscanResponse
import uk.gov.hmrc.transitmovementsrouter.models.responses.UpscanResponse.DownloadUrl
import uk.gov.hmrc.transitmovementsrouter.services.EISMessageTransformers
import uk.gov.hmrc.transitmovementsrouter.services.MessageTypeExtractor
import uk.gov.hmrc.transitmovementsrouter.services.ObjectStoreService
import uk.gov.hmrc.transitmovementsrouter.services.RoutingService
import uk.gov.hmrc.transitmovementsrouter.services.error.RoutingError

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
    with TestModelGenerators {

  val eori         = EoriNumber("eori")
  val movementType = MovementType("departures")
  val movementId   = MovementId("abcdef1234567890")
  val messageId    = MessageId("0987654321fedcba")

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
  val mockUpscanResponseParser       = mock[UpscanResponseParser]
  val mockObjectStoreService         = mock[ObjectStoreService]
  implicit val temporaryFileCreator  = SingletonTemporaryFileCreator

  val errorHandler                    = new DefaultHttpErrorHandler(HttpErrorConfig(showDevErrors = false, None), None, None)
  val controllerComponentWithTempFile = stubControllerComponents(playBodyParsers = PlayBodyParsers(SingletonTemporaryFileCreator, errorHandler)(materializer))

  object FakeAuthenticateEISToken extends AuthenticateEISToken {
    override protected def filter[A](request: Request[A]): Future[Option[Result]] = Future.successful(None)

    override def parser: BodyParser[AnyContent] = stubControllerComponents().parsers.defaultBodyParser

    override protected def executionContext: ExecutionContext = scala.concurrent.ExecutionContext.global
  }

  val mockMessageTypeExtractor: MessageTypeExtractor = mock[MessageTypeExtractor]

  def controller(eisMessageTransformer: EISMessageTransformers = new FakeXmlTransformer(trimmedXml)) =
    new MessagesController(
      controllerComponentWithTempFile,
      mockRoutingService,
      mockPersistenceConnector,
      mockPushNotificationsConnector,
      mockMessageTypeExtractor,
      FakeAuthenticateEISToken,
      eisMessageTransformer,
      mockObjectStoreService
    )

  def source = createStream(cc015cOfficeOfDepartureGB)

  val outgoing             = routes.MessagesController.outgoing(eori, movementType, movementId, messageId).url
  val incoming             = routes.MessagesController.incoming(ConversationId(movementId, messageId)).url
  val incomingLargeMessage = routes.MessagesController.incomingLargeMessage(movementId, messageId).url

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

  def fakeRequestLargeMessage[A](
    body: JsValue,
    url: String
  ): Request[JsValue] =
    FakeRequest(
      method = POST,
      uri = url,
      headers = FakeHeaders(Seq(HeaderNames.CONTENT_TYPE -> MimeTypes.JSON)),
      body = body
    )

  override def afterEach(): Unit = {
    reset(mockRoutingService)
    reset(mockMessageTypeExtractor)
    reset(mockPersistenceConnector)
    reset(mockObjectStoreService)
    super.afterEach()
  }

  lazy val submitDeclarationEither: EitherT[Future, RoutingError, Unit] =
    EitherT.rightT(())

  lazy val messageTypeHeader = FakeHeaders(Seq(("X-Message-Type", MessageType.DeclarationData.code)))

  val jsonSuccessUpscanResponse = Json.obj(
    "reference"   -> "11370e18-6e24-453e-b45a-76d3e32ea33d",
    "downloadUrl" -> "https://bucketName.s3.eu-west-2.amazonaws.com?1235676",
    "fileStatus"  -> "READY",
    "uploadDetails" -> Json.obj(
      "fileName"        -> "test.pdf",
      "fileMimeType"    -> "application/pdf",
      "uploadTimestamp" -> "2018-04-24T09:30:00Z",
      "checksum"        -> "396f101dd52e8b2ace0dcf5ed09b1d1f030e608938510ce46e7a5c7a4e775100",
      "size"            -> 987
    )
  )

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

      when(mockMessageTypeExtractor.extractFromHeaders(any())).thenReturn(EitherT.rightT[Future, MessageTypeExtractionError](MessageType.DeclarationData))

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
          when(mockMessageTypeExtractor.extractFromHeaders(any())).thenReturn(EitherT.rightT[Future, MessageTypeExtractionError](MessageType.DeclarationData))

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
        when(mockMessageTypeExtractor.extractFromHeaders(any())).thenReturn(EitherT.rightT[Future, MessageTypeExtractionError](MessageType.DeclarationData))

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
        when(mockMessageTypeExtractor.extractFromHeaders(any())).thenReturn(EitherT.rightT[Future, MessageTypeExtractionError](MessageType.DeclarationData))

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
        when(mockMessageTypeExtractor.extractFromHeaders(any()))
          .thenReturn(EitherT.leftT[Future, MessageType](MessageTypeExtractionError.UnableToExtractFromHeader))

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
        when(mockMessageTypeExtractor.extractFromHeaders(any()))
          .thenReturn(EitherT.leftT[Future, MessageType](MessageTypeExtractionError.InvalidMessageType("EEinvalid")))

        val result = controller().outgoing(eori, movementType, movementId, messageId)(
          fakeRequest(cc015cOfficeOfDepartureGB, outgoing, FakeHeaders(Seq(("X-Message-Type", "EEinvalid"))))
        )

        status(result) mustBe BAD_REQUEST
        contentAsJson(result) mustBe Json.obj(
          "code"    -> "BAD_REQUEST",
          "message" -> "Invalid message type: EEinvalid"
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
      when(mockMessageTypeExtractor.extractFromHeaders(any())).thenReturn(EitherT.rightT[Future, MessageTypeExtractionError](MessageType.DeclarationData))

      val result = controller().outgoing(eori, movementType, movementId, messageId)(fakeRequest(cc015cOfficeOfDepartureGB, outgoing, messageTypeHeader))

      status(result) mustBe INTERNAL_SERVER_ERROR
      contentAsJson(result) mustBe Json.obj(
        "code"    -> "INTERNAL_SERVER_ERROR",
        "message" -> "Internal server error"
      )
    }

    "must return BAD_REQUEST when a message is not a request message" in {

      when(mockMessageTypeExtractor.extractFromHeaders(any())).thenReturn(EitherT.rightT[Future, MessageTypeExtractionError](MessageType.Discrepancies))
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
      when(mockPersistenceConnector.postBody(any[String].asInstanceOf[MovementId], any[String].asInstanceOf[MessageId], any(), any())(any(), any()))
        .thenReturn(EitherT.fromEither(Right(PersistenceResponse(MessageId("1")))))
      when(mockMessageTypeExtractor.extract(any(), any())).thenReturn(EitherT.rightT[Future, MessageTypeExtractionError](MessageType.RequestOfRelease))

      val request = fakeRequest(incomingXml, incoming)
        .withHeaders(FakeHeaders().add("X-Message-Type" -> RequestOfRelease.code))

      val result = controller().incoming(ConversationId(movementId, messageId))(request)

      status(result) mustBe CREATED
      header("X-Message-Id", result) mustBe Some("1")
    }

    "must return BAD_REQUEST when the X-Message-Type header is missing or body seems to not contain an appropriate root tag" in {

      when(mockMessageTypeExtractor.extract(any(), any())).thenReturn(EitherT.leftT[Future, MessageType](MessageTypeExtractionError.UnableToExtractFromBody))
      val result = controller().incoming(ConversationId(movementId, messageId))(fakeRequest(incomingXml, incoming))

      status(result) mustBe BAD_REQUEST

    }

    "must return BAD_REQUEST when message type is invalid" in {

      val request = fakeRequest(incomingXml, incoming)
        .withHeaders(FakeHeaders().add("X-Message-Type" -> "abcdef"))
      when(mockMessageTypeExtractor.extract(any(), any()))
        .thenReturn(EitherT.leftT[Future, MessageType](MessageTypeExtractionError.InvalidMessageType("abcde")))

      val result = controller().incoming(ConversationId(movementId, messageId))(request)

      status(result) mustBe BAD_REQUEST
    }

    "must return NOT_FOUND when target movement is invalid or archived" in {

      when(mockPersistenceConnector.postBody(any[String].asInstanceOf[MovementId], any[String].asInstanceOf[MessageId], any(), any())(any(), any()))
        .thenReturn(EitherT.fromEither(Left(MovementNotFound(MovementId("ABC")))))
      when(mockMessageTypeExtractor.extract(any(), any())).thenReturn(EitherT.rightT[Future, MessageTypeExtractionError](MessageType.RequestOfRelease))

      val request = fakeRequest(incomingXml, incoming)
        .withHeaders(FakeHeaders().add("X-Message-Type" -> RequestOfRelease.code))

      val result = controller().incoming(ConversationId(movementId, messageId))(request)

      status(result) mustBe NOT_FOUND
    }

    "must return INTERNAL_SERVER_ERROR when persistence service fails unexpected" in {

      when(mockPersistenceConnector.postBody(any[String].asInstanceOf[MovementId], any[String].asInstanceOf[MessageId], any(), any())(any(), any()))
        .thenReturn(EitherT.fromEither(Left(Unexpected(None))))
      when(mockMessageTypeExtractor.extract(any(), any())).thenReturn(EitherT.rightT[Future, MessageTypeExtractionError](MessageType.RequestOfRelease))

      val request = fakeRequest(incomingXml, incoming)
        .withHeaders(FakeHeaders().add("X-Message-Type" -> RequestOfRelease.code))

      val result = controller().incoming(ConversationId(movementId, messageId))(request)

      status(result) mustBe INTERNAL_SERVER_ERROR
    }

  }

  "POST incoming for Large message" - {

    "must return CREATED when message is successfully forwarded" in forAll(
      arbitraryUpscanResponse(true).arbitrary,
      arbitraryMovementId.arbitrary,
      arbitraryMessageId.arbitrary,
      arbitraryObjectSummaryWithMd5.arbitrary
    ) {
      (successUpscanResponse, movementId, messageId, objectSummary) =>
        when(
          mockPersistenceConnector.postObjectStoreUri(
            any[String].asInstanceOf[MovementId],
            any[String].asInstanceOf[MessageId],
            any[String].asInstanceOf[MessageType],
            any[String].asInstanceOf[ObjectStoreURI]
          )(any(), any())
        )
          .thenReturn(EitherT.fromEither(Right(PersistenceResponse(messageId))))

        when(
          mockObjectStoreService.addMessage(any[String].asInstanceOf[DownloadUrl], any[String].asInstanceOf[MovementId], any[String].asInstanceOf[MessageId])(
            any(),
            any()
          )
        ).thenReturn(EitherT.rightT(objectSummary))

        val request = fakeRequestLargeMessage(Json.toJson(successUpscanResponse), incomingLargeMessage)

        val result = controller().incomingLargeMessage(movementId, messageId)(request)

        status(result) mustBe CREATED
        header("X-Message-Id", result) mustBe Some(messageId.value)
    }

    "must return NOT_FOUND when target movement is invalid or archived" in forAll(
      arbitraryUpscanResponse(true).arbitrary,
      arbitraryMovementId.arbitrary,
      arbitraryMessageId.arbitrary,
      arbitraryObjectSummaryWithMd5.arbitrary
    ) {
      (successUpscanResponse, movementId, messageId, objectSummary) =>
        when(
          mockPersistenceConnector.postObjectStoreUri(
            any[String].asInstanceOf[MovementId],
            any[String].asInstanceOf[MessageId],
            any[String].asInstanceOf[MessageType],
            any[String].asInstanceOf[ObjectStoreURI]
          )(any(), any())
        )
          .thenReturn(EitherT.fromEither(Left(MovementNotFound(MovementId("ABC")))))

        when(
          mockObjectStoreService.addMessage(any[String].asInstanceOf[DownloadUrl], any[String].asInstanceOf[MovementId], any[String].asInstanceOf[MessageId])(
            any(),
            any()
          )
        ).thenReturn(EitherT.rightT(objectSummary))

        val request = fakeRequestLargeMessage(Json.toJson(successUpscanResponse), incomingLargeMessage)
        val result  = controller().incomingLargeMessage(movementId, messageId)(request)

        status(result) mustBe NOT_FOUND
    }

    "must return BAD_REQUEST when malformed json received from callback" in forAll(
      arbitraryMovementId.arbitrary,
      arbitraryMessageId.arbitrary,
      arbitraryObjectSummaryWithMd5.arbitrary
    ) {

      (movementId, messageId, objectSummary) =>
        when(
          mockUpscanResponseParser.parseAndLogUpscanResponse(
            any[String].asInstanceOf[JsValue]
          )
        ).thenReturn(EitherT.fromEither(Left(PresentationError.badRequestError("Unexpected Upscan callback response"))))

        when(
          mockObjectStoreService.addMessage(any[String].asInstanceOf[DownloadUrl], any[String].asInstanceOf[MovementId], any[String].asInstanceOf[MessageId])(
            any(),
            any()
          )
        ).thenReturn(EitherT.rightT(objectSummary))

        val request = fakeRequestLargeMessage(Json.obj("reference" -> "abc"), incomingLargeMessage)
        val result  = controller().incomingLargeMessage(movementId, messageId)(request)

        status(result) mustBe BAD_REQUEST
    }

    "must return INTERNAL_SERVER_ERROR when persistence service fails unexpected" in forAll(
      arbitraryUpscanResponse(true).arbitrary,
      arbitraryMovementId.arbitrary,
      arbitraryMessageId.arbitrary,
      arbitraryObjectSummaryWithMd5.arbitrary
    ) {
      (successUpscanResponse, movementId, messageId, objectSummary) =>
        when(
          mockPersistenceConnector.postObjectStoreUri(
            any[String].asInstanceOf[MovementId],
            any[String].asInstanceOf[MessageId],
            any[String].asInstanceOf[MessageType],
            any[String].asInstanceOf[ObjectStoreURI]
          )(any(), any())
        )
          .thenReturn(EitherT.fromEither(Left(Unexpected(None))))

        when(
          mockObjectStoreService.addMessage(any[String].asInstanceOf[DownloadUrl], any[String].asInstanceOf[MovementId], any[String].asInstanceOf[MessageId])(
            any(),
            any()
          )
        ).thenReturn(EitherT.rightT(objectSummary))

        val request = fakeRequestLargeMessage(Json.toJson(successUpscanResponse), incomingLargeMessage)
        val result  = controller().incomingLargeMessage(movementId, messageId)(request)

        status(result) mustBe INTERNAL_SERVER_ERROR
    }

  }

  "should return Ok when response from upscan is valid" - {
    "and uploading to object-store succeeds" in forAll(
      arbitraryMovementId.arbitrary,
      arbitraryMessageId.arbitrary,
      arbitraryObjectSummaryWithMd5.arbitrary
    ) {
      (movementId, messageId, objectSummary) =>
        val request = FakeRequest(
          POST,
          routes.MessagesController.incomingLargeMessage(movementId, messageId).url,
          headers = FakeHeaders(),
          jsonSuccessUpscanResponse
        )

        when(
          mockPersistenceConnector.postObjectStoreUri(
            any[String].asInstanceOf[MovementId],
            any[String].asInstanceOf[MessageId],
            any[String].asInstanceOf[MessageType],
            any[String].asInstanceOf[ObjectStoreURI]
          )(any(), any())
        )
          .thenReturn(EitherT.fromEither(Right(PersistenceResponse(messageId))))

        when(
          mockObjectStoreService.addMessage(any[String].asInstanceOf[DownloadUrl], any[String].asInstanceOf[MovementId], any[String].asInstanceOf[MessageId])(
            any(),
            any()
          )
        ).thenReturn(EitherT.rightT(objectSummary))

        val result = controller().incomingLargeMessage(movementId, messageId)(request)

        status(result) mustBe CREATED
    }

    "and uploading to object-store fails" in forAll(arbitraryMovementId.arbitrary, arbitraryMessageId.arbitrary) {
      (movementId, messageId) =>
        when(
          mockPersistenceConnector.postObjectStoreUri(
            any[String].asInstanceOf[MovementId],
            any[String].asInstanceOf[MessageId],
            any[String].asInstanceOf[MessageType],
            any[String].asInstanceOf[ObjectStoreURI]
          )(any(), any())
        )
          .thenReturn(EitherT.fromEither(Right(PersistenceResponse(messageId))))

        when(
          mockObjectStoreService.addMessage(any[String].asInstanceOf[DownloadUrl], any[String].asInstanceOf[MovementId], any[String].asInstanceOf[MessageId])(
            any(),
            any()
          )
        ).thenReturn(EitherT.leftT(ObjectStoreError.UnexpectedError(None)))

        val request = FakeRequest(
          POST,
          routes.MessagesController.incomingLargeMessage(movementId, messageId).url,
          headers = FakeHeaders(),
          jsonSuccessUpscanResponse
        )

        val result = controller().incomingLargeMessage(movementId, messageId)(request)

        status(result) mustBe INTERNAL_SERVER_ERROR
    }
  }

}
