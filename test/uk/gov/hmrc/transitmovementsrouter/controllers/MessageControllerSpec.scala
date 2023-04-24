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
import org.mockito.ArgumentMatchersSugar.eqTo
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.MockitoSugar.reset
import org.mockito.MockitoSugar.when
import org.scalacheck.Gen
import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.ScalaFutures
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
import uk.gov.hmrc.objectstore.client.ObjectSummaryWithMd5
import uk.gov.hmrc.transitmovementsrouter.base.StreamTestHelpers.createStream
import uk.gov.hmrc.transitmovementsrouter.base.TestActorSystem
import uk.gov.hmrc.transitmovementsrouter.config.AppConfig
import uk.gov.hmrc.transitmovementsrouter.connectors.PersistenceConnector
import uk.gov.hmrc.transitmovementsrouter.connectors.PushNotificationsConnector
import uk.gov.hmrc.transitmovementsrouter.connectors.UpscanConnector
import uk.gov.hmrc.transitmovementsrouter.controllers.actions.AuthenticateEISToken
import uk.gov.hmrc.transitmovementsrouter.controllers.errors.PresentationError
import uk.gov.hmrc.transitmovementsrouter.fakes.actions.FakeXmlTransformer
import uk.gov.hmrc.transitmovementsrouter.generators.TestModelGenerators
import uk.gov.hmrc.transitmovementsrouter.models.MessageType.RequestOfRelease
import uk.gov.hmrc.transitmovementsrouter.models._
import uk.gov.hmrc.transitmovementsrouter.models.errors.PersistenceError.MovementNotFound
import uk.gov.hmrc.transitmovementsrouter.models.errors.PersistenceError.Unexpected
import uk.gov.hmrc.transitmovementsrouter.models.errors._
import uk.gov.hmrc.transitmovementsrouter.models.requests.MessageUpdate
import uk.gov.hmrc.transitmovementsrouter.models.responses.UpscanResponse.DownloadUrl
import uk.gov.hmrc.transitmovementsrouter.models.sdes.SdesNotificationType
import uk.gov.hmrc.transitmovementsrouter.services._
import uk.gov.hmrc.transitmovementsrouter.services.error.RoutingError

import java.util.UUID
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
    with TestModelGenerators
    with ScalaFutures {

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
    <TraderChannelResponse>
      <ncts:CC013C PhaseID="NCTS5.0" xmlns:ncts="http://ncts.dgtaxud.ec">text</ncts:CC013C>
    </TraderChannelResponse>
  val trimmedXml: NodeSeq = <ncts:CC013C PhaseID="NCTS5.0" xmlns:ncts="http://ncts.dgtaxud.ec">text</ncts:CC013C>

  val mockRoutingService               = mock[RoutingService]
  val mockPersistenceConnector         = mock[PersistenceConnector]
  val mockPushNotificationsConnector   = mock[PushNotificationsConnector]
  val mockUpscanResponseParser         = mock[UpscanResponseParser]
  val mockObjectStoreService           = mock[ObjectStoreService]
  val mockObjectStoreURIExtractor      = mock[ObjectStoreURIExtractor]
  val mockCustomOfficeExtractorService = mock[CustomOfficeExtractorService]
  val mockSDESService                  = mock[SDESService]
  val mockSdesResponseParser           = mock[SdesResponseParser]
  val mockUpscanConnector              = mock[UpscanConnector]

  implicit val temporaryFileCreator = SingletonTemporaryFileCreator

  val errorHandler                    = new DefaultHttpErrorHandler(HttpErrorConfig(showDevErrors = false, None), None, None)
  val controllerComponentWithTempFile = stubControllerComponents(playBodyParsers = PlayBodyParsers(SingletonTemporaryFileCreator, errorHandler)(materializer))

  object FakeAuthenticateEISToken extends AuthenticateEISToken {
    override protected def filter[A](request: Request[A]): Future[Option[Result]] = Future.successful(None)

    override def parser: BodyParser[AnyContent] = stubControllerComponents().parsers.defaultBodyParser

    override protected def executionContext: ExecutionContext = scala.concurrent.ExecutionContext.global
  }

  val mockMessageTypeExtractor: MessageTypeExtractor = mock[MessageTypeExtractor]
  val config: AppConfig                              = mock[AppConfig]
  when(config.logIncoming).thenReturn(true)
  when(config.eisSizeLimit).thenReturn(5000000) // 5MB, TODO: we'll need to vary this per test

  def controller(eisMessageTransformer: EISMessageTransformers = new FakeXmlTransformer(trimmedXml)) =
    new MessagesController(
      controllerComponentWithTempFile,
      mockRoutingService,
      mockPersistenceConnector,
      mockPushNotificationsConnector,
      mockMessageTypeExtractor,
      FakeAuthenticateEISToken,
      eisMessageTransformer,
      mockObjectStoreService,
      mockUpscanConnector,
      mockCustomOfficeExtractorService,
      mockSDESService,
      config
    )

  def source = createStream(cc015cOfficeOfDepartureGB)

  val outgoing             = routes.MessagesController.outgoing(eori, movementType, movementId, messageId).url
  val incoming             = routes.MessagesController.incomingViaEIS(ConversationId(movementId, messageId)).url
  val incomingLargeMessage = routes.MessagesController.incomingViaUpscan(movementId, messageId).url
  val sdesCallback         = routes.MessagesController.handleSdesResponse().url

  def fakeRequest[A](
    body: NodeSeq,
    url: String,
    headers: FakeHeaders = FakeHeaders(Seq.empty)
  ): Request[Source[ByteString, _]] =
    FakeRequest(
      method = POST,
      uri = url,
      headers = headers.add(HeaderNames.CONTENT_TYPE -> MimeTypes.XML),
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
    reset(mockCustomOfficeExtractorService)
    reset(mockSDESService)
    reset(mockPushNotificationsConnector)
    reset(mockUpscanConnector)
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
          any[Source[ByteString, _]],
          any[String].asInstanceOf[CustomsOffice]
        )(any[HeaderCarrier], any[ExecutionContext])
      ).thenReturn(submitDeclarationEither)

      when(
        mockPushNotificationsConnector
          .postXML(any[String].asInstanceOf[MovementId], any[String].asInstanceOf[MessageId], any[Source[ByteString, _]])(
            any[HeaderCarrier],
            any[ExecutionContext]
          )
      ).thenReturn(EitherT.rightT(()))

      when(mockMessageTypeExtractor.extractFromHeaders(any())).thenReturn(EitherT.rightT[Future, MessageTypeExtractionError](MessageType.DeclarationData))

      when(mockCustomOfficeExtractorService.extractCustomOffice(any(), any()))
        .thenReturn(EitherT.rightT[Future, CustomOfficeExtractorError](CustomsOffice("GB1234567")))

      val result = controller().outgoing(eori, movementType, movementId, messageId)(fakeRequest(cc015cOfficeOfDepartureGB, outgoing, messageTypeHeader))

      status(result) mustBe ACCEPTED
    }

    "must return BAD_REQUEST when declaration submission fails" - {

      "returns INVALID_OFFICE when an invalid custom office supplied in payload" in forAll(Gen.alphaNumStr, Gen.alphaStr) {
        (office, field) =>
          when(mockCustomOfficeExtractorService.extractCustomOffice(any(), any()))
            .thenReturn(
              EitherT[Future, CustomOfficeExtractorError, CustomsOffice](
                Future.successful(Left(CustomOfficeExtractorError.UnrecognisedOffice("office", CustomsOffice(office), field)))
              )
            )

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

      "returns message to indicate element not found" in {

        when(mockCustomOfficeExtractorService.extractCustomOffice(any(), any()))
          .thenReturn(
            EitherT[Future, CustomOfficeExtractorError, CustomsOffice](Future.successful(Left(CustomOfficeExtractorError.NoElementFound("messageSender"))))
          )

        when(mockMessageTypeExtractor.extractFromHeaders(any())).thenReturn(EitherT.rightT[Future, MessageTypeExtractionError](MessageType.DeclarationData))

        val result = controller().outgoing(eori, movementType, movementId, messageId)(fakeRequest(cc015cOfficeOfDepartureGB, outgoing, messageTypeHeader))

        status(result) mustBe BAD_REQUEST
        contentAsJson(result) mustBe Json.obj(
          "code"    -> "BAD_REQUEST",
          "message" -> "Element messageSender not found"
        )
      }

      "returns message to indicate too many elements" in {

        when(mockCustomOfficeExtractorService.extractCustomOffice(any(), any()))
          .thenReturn(
            EitherT[Future, CustomOfficeExtractorError, CustomsOffice](Future.successful(Left(CustomOfficeExtractorError.TooManyElementsFound("eori"))))
          )

        when(mockMessageTypeExtractor.extractFromHeaders(any())).thenReturn(EitherT.rightT[Future, MessageTypeExtractionError](MessageType.DeclarationData))

        val result = controller().outgoing(eori, movementType, movementId, messageId)(fakeRequest(cc015cOfficeOfDepartureGB, outgoing, messageTypeHeader))

        status(result) mustBe BAD_REQUEST
        contentAsJson(result) mustBe Json.obj(
          "code"    -> "BAD_REQUEST",
          "message" -> "Found too many elements of type eori"
        )
      }

      "returns message to inform that the X-Message-Type header is not present" in {

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

        when(mockMessageTypeExtractor.extractFromHeaders(any()))
          .thenReturn(EitherT.leftT[Future, MessageType](MessageTypeExtractionError.InvalidMessageType("invalid")))

        val result = controller().outgoing(eori, movementType, movementId, messageId)(
          fakeRequest(cc015cOfficeOfDepartureGB, outgoing, FakeHeaders(Seq(("X-Message-Type", "invalid"))))
        )

        status(result) mustBe BAD_REQUEST
        contentAsJson(result) mustBe Json.obj(
          "code"    -> "BAD_REQUEST",
          "message" -> "Invalid message type: invalid"
        )
      }

      "returns message is not a request message" in {

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

    "must return INTERNAL_SERVER_ERROR when declaration submission fails due to unexpected error" in {

      when(
        mockRoutingService.submitMessage(
          any[String].asInstanceOf[MovementType],
          any[String].asInstanceOf[MovementId],
          any[String].asInstanceOf[MessageId],
          any[Source[ByteString, _]],
          any[String].asInstanceOf[CustomsOffice]
        )(any[HeaderCarrier], any[ExecutionContext])
      ).thenReturn(
        EitherT[Future, RoutingError, Unit](
          Future.successful(Left(RoutingError.Unexpected("unexpected error", Some(new Exception("An unexpected error occurred")))))
        )
      )

      when(mockCustomOfficeExtractorService.extractCustomOffice(any(), any()))
        .thenReturn(EitherT.rightT[Future, CustomOfficeExtractorError](CustomsOffice("GB1234567")))

      when(mockMessageTypeExtractor.extractFromHeaders(any())).thenReturn(EitherT.rightT[Future, MessageTypeExtractionError](MessageType.DeclarationData))

      val result = controller().outgoing(eori, movementType, movementId, messageId)(fakeRequest(cc015cOfficeOfDepartureGB, outgoing, messageTypeHeader))

      status(result) mustBe INTERNAL_SERVER_ERROR
      contentAsJson(result) mustBe Json.obj(
        "code"    -> "INTERNAL_SERVER_ERROR",
        "message" -> "Internal server error"
      )
    }

  }

  "POST outgoing Large message" - {
    "must return ACCEPTED when declaration is submitted successfully" in forAll(
      arbitraryMovementId.arbitrary,
      arbitraryMessageId.arbitrary,
      arbitraryObjectSummaryWithMd5.arbitrary,
      arbitraryObjectStoreResourceLocation.arbitrary
    ) {
      (movementId, messageId, objectSummary, objectStoreResourceLocation) =>
        when(mockMessageTypeExtractor.extractFromHeaders(any())).thenReturn(EitherT.rightT[Future, MessageTypeExtractionError](MessageType.DeclarationData))

        when(mockObjectStoreURIExtractor.extractObjectStoreURIHeader(any[Headers]))
          .thenReturn(EitherT.rightT(objectStoreResourceLocation))

        when(mockObjectStoreService.getObjectStoreFile(any[String].asInstanceOf[ObjectStoreResourceLocation])(any[HeaderCarrier], any[ExecutionContext]))
          .thenReturn(EitherT.rightT(source))

        when(mockCustomOfficeExtractorService.extractCustomOffice(any(), any()))
          .thenReturn(EitherT.rightT[Future, CustomOfficeExtractorError](CustomsOffice("GB1234567")))

        when(
          mockObjectStoreService.storeOutgoing(
            any[String].asInstanceOf[ObjectStoreResourceLocation]
          )(
            any(),
            any()
          )
        ).thenReturn(EitherT.rightT(objectSummary))

        when(
          mockSDESService.send(
            eqTo(MovementId(movementId.value)),
            eqTo(MessageId(messageId.value)),
            eqTo(ObjectSummaryWithMd5(objectSummary.location, objectSummary.contentLength, objectSummary.contentMd5, objectSummary.lastModified))
          )(any[ExecutionContext], any[HeaderCarrier])
        ).thenReturn(EitherT.rightT(()))

        lazy val request = FakeRequest(
          method = "POST",
          uri = outgoing,
          headers = FakeHeaders(Seq("X-Message-Type" -> MessageType.DeclarationData.code, "X-Object-Store-Uri" -> objectStoreResourceLocation.contextPath)),
          body = AnyContentAsEmpty
        )

        val result = controller().outgoing(eori, movementType, movementId, messageId)(request)

        status(result) mustBe ACCEPTED
    }

    "must return message to inform that the X-Message-Type header is not present" in forAll(
      arbitraryMovementId.arbitrary,
      arbitraryMessageId.arbitrary,
      arbitraryObjectStoreResourceLocation.arbitrary
    ) {
      (movementId, messageId, objectStoreResourceLocation) =>
        when(mockMessageTypeExtractor.extractFromHeaders(any()))
          .thenReturn(EitherT.leftT[Future, MessageType](MessageTypeExtractionError.UnableToExtractFromHeader))

        val request = FakeRequest(
          method = "POST",
          uri = outgoing,
          headers = FakeHeaders(Seq("X-Object-Store-Uri" -> objectStoreResourceLocation.contextPath)),
          body = AnyContentAsEmpty
        )

        val result = controller().outgoing(eori, movementType, movementId, messageId)(request)

        status(result) mustBe BAD_REQUEST
        contentAsJson(result) mustBe Json.obj(
          "code"    -> "BAD_REQUEST",
          "message" -> "Missing header: X-Message-Type"
        )
    }

    "must return message to inform that the X-Message-Type header value is invalid" in forAll(
      arbitraryMovementId.arbitrary,
      arbitraryMessageId.arbitrary,
      arbitraryObjectStoreResourceLocation.arbitrary
    ) {
      (movementId, messageId, objectStoreResourceLocation) =>
        when(mockMessageTypeExtractor.extractFromHeaders(any()))
          .thenReturn(EitherT.leftT[Future, MessageType](MessageTypeExtractionError.InvalidMessageType("EEinvalid")))

        val request = FakeRequest(
          method = "POST",
          uri = outgoing,
          headers = FakeHeaders(Seq("X-Message-Type" -> MessageType.DeclarationData.code, "X-Object-Store-Uri" -> objectStoreResourceLocation.contextPath)),
          body = AnyContentAsEmpty
        )

        val result = controller().outgoing(eori, movementType, movementId, messageId)(request)

        status(result) mustBe BAD_REQUEST
        contentAsJson(result) mustBe Json.obj(
          "code"    -> "BAD_REQUEST",
          "message" -> "Invalid message type: EEinvalid"
        )
    }

    "must return BAD_REQUEST when a message is not a request message" in {

      when(mockMessageTypeExtractor.extractFromHeaders(any())).thenReturn(EitherT.rightT[Future, MessageTypeExtractionError](MessageType.Discrepancies))

      val request = FakeRequest(
        method = "POST",
        uri = outgoing,
        headers = FakeHeaders(Seq("X-Message-Type" -> MessageType.Discrepancies.code)),
        body = AnyContentAsEmpty
      )
      val result = controller().outgoing(eori, movementType, movementId, messageId)(request)

      status(result) mustBe BAD_REQUEST
      contentAsJson(result) mustBe Json.obj(
        "code"    -> "BAD_REQUEST",
        "message" -> s"${MessageType.Discrepancies.code} is not valid for requests"
      )
    }

    "must return message to inform that the X-Object-Store-Uri header is not present" in forAll(
      arbitraryMovementId.arbitrary,
      arbitraryMessageId.arbitrary
    ) {
      (movementId, messageId) =>
        when(mockMessageTypeExtractor.extractFromHeaders(any())).thenReturn(EitherT.rightT[Future, MessageTypeExtractionError](MessageType.DeclarationData))
        when(mockObjectStoreURIExtractor.extractObjectStoreURIHeader(any[Headers]))
          .thenReturn(EitherT.leftT(PresentationError.badRequestError("Missing X-Object-Store-Uri header value")))

        val request = FakeRequest(
          method = "POST",
          uri = outgoing,
          headers = FakeHeaders(Seq("X-Message-Type" -> MessageType.DeclarationData.code)),
          body = AnyContentAsEmpty
        )
        val result = controller().outgoing(eori, movementType, movementId, messageId)(request)

        status(result) mustBe BAD_REQUEST
        contentAsJson(result) mustBe Json.obj(
          "code"    -> "BAD_REQUEST",
          "message" -> "Missing X-Object-Store-Uri header value"
        )
    }

    "must return BAD_REQUEST when file not found on object store resource location" in forAll(
      arbitraryMovementId.arbitrary,
      arbitraryMessageId.arbitrary,
      arbitraryObjectStoreResourceLocation.arbitrary
    ) {
      (movementId, messageId, objectStoreResourceLocation) =>
        when(mockMessageTypeExtractor.extractFromHeaders(any())).thenReturn(EitherT.rightT[Future, MessageTypeExtractionError](MessageType.DeclarationData))

        when(mockObjectStoreURIExtractor.extractObjectStoreURIHeader(any[Headers]))
          .thenReturn(EitherT.rightT(objectStoreResourceLocation))

        when(mockObjectStoreService.getObjectStoreFile(any[String].asInstanceOf[ObjectStoreResourceLocation])(any[HeaderCarrier], any[ExecutionContext]))
          .thenReturn(EitherT.leftT(ObjectStoreError.FileNotFound(objectStoreResourceLocation.contextPath)))

        val request = FakeRequest(
          method = "POST",
          uri = outgoing,
          headers = FakeHeaders(Seq("X-Message-Type" -> MessageType.DeclarationData.code, "X-Object-Store-Uri" -> objectStoreResourceLocation.contextPath)),
          body = AnyContentAsEmpty
        )
        val result = controller().outgoing(eori, movementType, movementId, messageId)(request)

        status(result) mustBe BAD_REQUEST
        contentAsJson(result) mustBe Json.obj(
          "code"    -> "BAD_REQUEST",
          "message" -> s"file not found at location: ${objectStoreResourceLocation.contextPath}"
        )
    }

    "must return INVALID_OFFICE when an invalid custom office supplied in payload" in forAll(
      arbitraryMovementId.arbitrary,
      arbitraryMessageId.arbitrary,
      arbitraryObjectStoreResourceLocation.arbitrary,
      Gen.alphaNumStr,
      Gen.alphaStr
    ) {
      (movementId, messageId, objectStoreResourceLocation, office, field) =>
        when(mockMessageTypeExtractor.extractFromHeaders(any())).thenReturn(EitherT.rightT[Future, MessageTypeExtractionError](MessageType.DeclarationData))

        when(mockObjectStoreURIExtractor.extractObjectStoreURIHeader(any[Headers]))
          .thenReturn(EitherT.rightT(objectStoreResourceLocation))

        when(mockObjectStoreService.getObjectStoreFile(any[String].asInstanceOf[ObjectStoreResourceLocation])(any[HeaderCarrier], any[ExecutionContext]))
          .thenReturn(EitherT.rightT(source))

        when(mockCustomOfficeExtractorService.extractCustomOffice(any(), any()))
          .thenReturn(
            EitherT[Future, CustomOfficeExtractorError, CustomsOffice](
              Future.successful(Left(CustomOfficeExtractorError.UnrecognisedOffice("office", CustomsOffice(office), field)))
            )
          )

        val request = FakeRequest(
          method = "POST",
          uri = outgoing,
          headers = FakeHeaders(Seq("X-Message-Type" -> MessageType.DeclarationData.code, "X-Object-Store-Uri" -> objectStoreResourceLocation.contextPath)),
          body = AnyContentAsEmpty
        )
        val result = controller().outgoing(eori, movementType, movementId, messageId)(request)

        status(result) mustBe BAD_REQUEST
        contentAsJson(result) mustBe Json.obj(
          "code"    -> "INVALID_OFFICE",
          "message" -> "office",
          "office"  -> office,
          "field"   -> field
        )
    }

    "must return message to indicate element not found" in forAll(
      arbitraryMovementId.arbitrary,
      arbitraryMessageId.arbitrary,
      arbitraryObjectStoreResourceLocation.arbitrary
    ) {
      (movementId, messageId, objectStoreResourceLocation) =>
        when(mockMessageTypeExtractor.extractFromHeaders(any())).thenReturn(EitherT.rightT[Future, MessageTypeExtractionError](MessageType.DeclarationData))

        when(mockObjectStoreURIExtractor.extractObjectStoreURIHeader(any[Headers]))
          .thenReturn(EitherT.rightT(objectStoreResourceLocation))

        when(mockObjectStoreService.getObjectStoreFile(any[String].asInstanceOf[ObjectStoreResourceLocation])(any[HeaderCarrier], any[ExecutionContext]))
          .thenReturn(EitherT.rightT(source))

        when(mockCustomOfficeExtractorService.extractCustomOffice(any(), any()))
          .thenReturn(
            EitherT[Future, CustomOfficeExtractorError, CustomsOffice](Future.successful(Left(CustomOfficeExtractorError.NoElementFound("messageSender"))))
          )

        val request = FakeRequest(
          method = "POST",
          uri = outgoing,
          headers = FakeHeaders(Seq("X-Message-Type" -> MessageType.DeclarationData.code, "X-Object-Store-Uri" -> objectStoreResourceLocation.contextPath)),
          body = AnyContentAsEmpty
        )
        val result = controller().outgoing(eori, movementType, movementId, messageId)(request)

        status(result) mustBe BAD_REQUEST
        contentAsJson(result) mustBe Json.obj(
          "code"    -> "BAD_REQUEST",
          "message" -> "Element messageSender not found"
        )
    }

    "must return message to indicate too many elements" in forAll(
      arbitraryMovementId.arbitrary,
      arbitraryMessageId.arbitrary,
      arbitraryObjectStoreResourceLocation.arbitrary
    ) {
      (movementId, messageId, objectStoreResourceLocation) =>
        when(mockMessageTypeExtractor.extractFromHeaders(any())).thenReturn(EitherT.rightT[Future, MessageTypeExtractionError](MessageType.DeclarationData))

        when(mockObjectStoreURIExtractor.extractObjectStoreURIHeader(any[Headers]))
          .thenReturn(EitherT.rightT(objectStoreResourceLocation))

        when(mockObjectStoreService.getObjectStoreFile(any[String].asInstanceOf[ObjectStoreResourceLocation])(any[HeaderCarrier], any[ExecutionContext]))
          .thenReturn(EitherT.rightT(source))

        when(mockCustomOfficeExtractorService.extractCustomOffice(any(), any()))
          .thenReturn(
            EitherT[Future, CustomOfficeExtractorError, CustomsOffice](Future.successful(Left(CustomOfficeExtractorError.TooManyElementsFound("eori"))))
          )
        val request = FakeRequest(
          method = "POST",
          uri = outgoing,
          headers = FakeHeaders(Seq("X-Message-Type" -> MessageType.DeclarationData.code, "X-Object-Store-Uri" -> objectStoreResourceLocation.contextPath)),
          body = AnyContentAsEmpty
        )
        val result = controller().outgoing(eori, movementType, movementId, messageId)(request)

        status(result) mustBe BAD_REQUEST
        contentAsJson(result) mustBe Json.obj(
          "code"    -> "BAD_REQUEST",
          "message" -> "Found too many elements of type eori"
        )
    }

    "must return INTERNAL_SERVER_ERROR when declaration submission fails due to unexpected error" in forAll(
      arbitraryMovementId.arbitrary,
      arbitraryMessageId.arbitrary,
      arbitraryObjectStoreResourceLocation.arbitrary
    ) {
      (movementId, messageId, objectStoreResourceLocation) =>
        when(mockMessageTypeExtractor.extractFromHeaders(any())).thenReturn(EitherT.rightT[Future, MessageTypeExtractionError](MessageType.DeclarationData))
        when(mockObjectStoreURIExtractor.extractObjectStoreURIHeader(any[Headers]))
          .thenReturn(EitherT.rightT(objectStoreResourceLocation))

        when(mockObjectStoreService.getObjectStoreFile(any[String].asInstanceOf[ObjectStoreResourceLocation])(any[HeaderCarrier], any[ExecutionContext]))
          .thenReturn(EitherT.rightT(source))

        when(mockCustomOfficeExtractorService.extractCustomOffice(any(), any()))
          .thenReturn(EitherT.rightT[Future, CustomOfficeExtractorError](CustomsOffice("GB1234567")))

        when(
          mockObjectStoreService.storeOutgoing(
            any[String].asInstanceOf[ObjectStoreResourceLocation]
          )(
            any(),
            any()
          )
        ).thenReturn(EitherT.leftT(ObjectStoreError.UnexpectedError(None)))

        val request = FakeRequest(
          method = "POST",
          uri = outgoing,
          headers = FakeHeaders(Seq("X-Message-Type" -> MessageType.DeclarationData.code, "X-Object-Store-Uri" -> objectStoreResourceLocation.contextPath)),
          body = AnyContentAsEmpty
        )

        val result = controller().outgoing(eori, movementType, movementId, messageId)(request)

        status(result) mustBe INTERNAL_SERVER_ERROR
        contentAsJson(result) mustBe Json.obj(
          "code"    -> "INTERNAL_SERVER_ERROR",
          "message" -> "Internal server error"
        )
    }

    "must return INTERNAL_SERVER_ERROR when submission fails to SDES due to unexpected error" in forAll(
      arbitraryMovementId.arbitrary,
      arbitraryMessageId.arbitrary,
      arbitraryObjectSummaryWithMd5.arbitrary,
      arbitraryObjectStoreResourceLocation.arbitrary
    ) {
      (movementId, messageId, objectSummary, objectStoreResourceLocation) =>
        when(
          mockMessageTypeExtractor.extractFromHeaders(
            eqTo(FakeHeaders(Seq("X-Message-Type" -> MessageType.DeclarationData.code, "X-Object-Store-Uri" -> objectStoreResourceLocation.contextPath)))
          )
        ).thenReturn(EitherT.rightT[Future, MessageTypeExtractionError](MessageType.DeclarationData))
        when(
          mockObjectStoreURIExtractor.extractObjectStoreURIHeader(
            eqTo(FakeHeaders(Seq("X-Message-Type" -> MessageType.DeclarationData.code, "X-Object-Store-Uri" -> objectStoreResourceLocation.contextPath)))
          )
        )
          .thenReturn(EitherT.rightT(objectStoreResourceLocation))

        when(
          mockObjectStoreService.getObjectStoreFile(
            eqTo(
              ObjectStoreResourceLocation(
                objectStoreResourceLocation.contextPath,
                objectStoreResourceLocation.resourceLocation
              )
            )
          )(any[HeaderCarrier], any[ExecutionContext])
        )
          .thenReturn(EitherT.rightT(source))

        when(mockCustomOfficeExtractorService.extractCustomOffice(any(), eqTo(MessageType.DeclarationData)))
          .thenReturn(EitherT.rightT[Future, CustomOfficeExtractorError](CustomsOffice("GB1234567")))

        when(
          mockObjectStoreService.storeOutgoing(
            eqTo(ObjectStoreResourceLocation(objectStoreResourceLocation.contextPath, objectStoreResourceLocation.resourceLocation))
          )(
            any(),
            any()
          )
        ).thenReturn(EitherT.rightT(objectSummary))

        when(
          mockSDESService.send(
            eqTo(MovementId(movementId.value)),
            eqTo(MessageId(messageId.value)),
            eqTo(ObjectSummaryWithMd5(objectSummary.location, objectSummary.contentLength, objectSummary.contentMd5, objectSummary.lastModified))
          )(any[ExecutionContext], any[HeaderCarrier])
        ).thenReturn(
          EitherT.leftT(SDESError.UnexpectedError(None))
        )

        val request = FakeRequest(
          method = "POST",
          uri = outgoing,
          headers = FakeHeaders(Seq("X-Message-Type" -> MessageType.DeclarationData.code, "X-Object-Store-Uri" -> objectStoreResourceLocation.contextPath)),
          body = AnyContentAsEmpty
        )

        val result = controller().outgoing(eori, movementType, movementId, messageId)(request)

        status(result) mustBe INTERNAL_SERVER_ERROR
        contentAsJson(result) mustBe Json.obj(
          "code"    -> "INTERNAL_SERVER_ERROR",
          "message" -> "Internal server error"
        )
    }

  }

  "POST incoming" - {
    "must return CREATED when message is successfully forwarded" in {
      when(mockPersistenceConnector.postBody(any[String].asInstanceOf[MovementId], any[String].asInstanceOf[MessageId], any(), any())(any(), any()))
        .thenReturn(EitherT.fromEither(Right(PersistenceResponse(MessageId("1")))))
      when(mockMessageTypeExtractor.extract(any(), any())).thenReturn(EitherT.rightT[Future, MessageTypeExtractionError](MessageType.RequestOfRelease))
      when(
        mockPushNotificationsConnector
          .postXML(any[String].asInstanceOf[MovementId], any[String].asInstanceOf[MessageId], any[Source[ByteString, _]])(
            any[HeaderCarrier],
            any[ExecutionContext]
          )
      ).thenReturn(EitherT.rightT(()))
      val request = fakeRequest(incomingXml, incoming)
        .withHeaders(FakeHeaders().add("X-Message-Type" -> RequestOfRelease.code))

      val result = controller().incomingViaEIS(ConversationId(movementId, messageId))(request)

      status(result) mustBe CREATED
      header("X-Message-Id", result) mustBe Some("1")
    }

    "must return BAD_REQUEST when the X-Message-Type header is missing or body seems to not contain an appropriate root tag" in {

      when(mockMessageTypeExtractor.extract(any(), any())).thenReturn(EitherT.leftT[Future, MessageType](MessageTypeExtractionError.UnableToExtractFromBody))
      val result = controller().incomingViaEIS(ConversationId(movementId, messageId))(fakeRequest(incomingXml, incoming))

      status(result) mustBe BAD_REQUEST

    }

    "must return BAD_REQUEST when message type is invalid" in {

      val request = fakeRequest(incomingXml, incoming)
        .withHeaders(FakeHeaders().add("X-Message-Type" -> "abcdef"))
      when(mockMessageTypeExtractor.extract(any(), any()))
        .thenReturn(EitherT.leftT[Future, MessageType](MessageTypeExtractionError.InvalidMessageType("abcde")))

      val result = controller().incomingViaEIS(ConversationId(movementId, messageId))(request)

      status(result) mustBe BAD_REQUEST
    }

    "must return NOT_FOUND when target movement is invalid or archived" in {

      when(mockPersistenceConnector.postBody(any[String].asInstanceOf[MovementId], any[String].asInstanceOf[MessageId], any(), any())(any(), any()))
        .thenReturn(EitherT.fromEither(Left(MovementNotFound(MovementId("ABC")))))
      when(mockMessageTypeExtractor.extract(any(), any())).thenReturn(EitherT.rightT[Future, MessageTypeExtractionError](MessageType.RequestOfRelease))

      val request = fakeRequest(incomingXml, incoming)
        .withHeaders(FakeHeaders().add("X-Message-Type" -> RequestOfRelease.code))

      val result = controller().incomingViaEIS(ConversationId(movementId, messageId))(request)

      status(result) mustBe NOT_FOUND
    }

    "must return INTERNAL_SERVER_ERROR when persistence service fails unexpected" in {

      when(mockPersistenceConnector.postBody(any[String].asInstanceOf[MovementId], any[String].asInstanceOf[MessageId], any(), any())(any(), any()))
        .thenReturn(EitherT.fromEither(Left(Unexpected(None))))
      when(mockMessageTypeExtractor.extract(any(), any())).thenReturn(EitherT.rightT[Future, MessageTypeExtractionError](MessageType.RequestOfRelease))

      val request = fakeRequest(incomingXml, incoming)
        .withHeaders(FakeHeaders().add("X-Message-Type" -> RequestOfRelease.code))

      val result = controller().incomingViaEIS(ConversationId(movementId, messageId))(request)

      status(result) mustBe INTERNAL_SERVER_ERROR
    }

  }

  "POST incoming for Large message" - {

    "must return CREATED when message is successfully forwarded" in forAll(
      arbitraryUpscanResponse(true).arbitrary,
      arbitraryMovementId.arbitrary,
      arbitraryMessageId.arbitrary,
      arbitraryObjectSummaryWithMd5.arbitrary,
      arbitraryObjectStoreResourceLocation.arbitrary
    ) {
      (successUpscanResponse, movementId, messageId, objectSummary, objectStoreResourceLocation) =>
        when(
          mockObjectStoreService.storeIncoming(
            any[String].asInstanceOf[DownloadUrl],
            any[String].asInstanceOf[MovementId],
            any[String].asInstanceOf[MessageId]
          )(
            any(),
            any()
          )
        ).thenReturn(EitherT.rightT(objectSummary))

        when(mockObjectStoreURIExtractor.extractObjectStoreResourceLocation(any[String].asInstanceOf[ObjectStoreURI]))
          .thenReturn(EitherT.rightT(objectStoreResourceLocation))

        when(mockObjectStoreService.getObjectStoreFile(any[String].asInstanceOf[ObjectStoreResourceLocation])(any[HeaderCarrier], any[ExecutionContext]))
          .thenReturn(EitherT.rightT(Source.single(ByteString("this is test content"))))

        when(mockMessageTypeExtractor.extractFromBody(any())).thenReturn(EitherT.rightT[Future, MessageTypeExtractionError](MessageType.DeclarationData))

        when(
          mockPersistenceConnector.postObjectStoreUri(
            any[String].asInstanceOf[MovementId],
            any[String].asInstanceOf[MessageId],
            any[String].asInstanceOf[MessageType],
            any[String].asInstanceOf[ObjectStoreURI]
          )(any(), any())
        ).thenReturn(EitherT.fromEither(Right(PersistenceResponse(messageId))))

        when(
          mockPushNotificationsConnector
            .post(any[String].asInstanceOf[MovementId], any[String].asInstanceOf[MessageId])(
              any(),
              any()
            )
        ).thenReturn(EitherT.rightT(()))

        val request = fakeRequestLargeMessage(Json.toJson(successUpscanResponse), incomingLargeMessage)

        val result = controller().incomingViaUpscan(movementId, messageId)(request)

        status(result) mustBe CREATED
        header("X-Message-Id", result) mustBe Some(messageId.value)
    }

    "must return NOT_FOUND when target movement is invalid or archived" in forAll(
      arbitraryUpscanResponse(true).arbitrary,
      arbitraryMovementId.arbitrary,
      arbitraryMessageId.arbitrary,
      arbitraryObjectSummaryWithMd5.arbitrary,
      arbitraryObjectStoreResourceLocation.arbitrary
    ) {
      (successUpscanResponse, movementId, messageId, objectSummary, objectStoreResourceLocation) =>
        when(
          mockObjectStoreService.storeIncoming(
            any[String].asInstanceOf[DownloadUrl],
            any[String].asInstanceOf[MovementId],
            any[String].asInstanceOf[MessageId]
          )(
            any(),
            any()
          )
        ).thenReturn(EitherT.rightT(objectSummary))

        when(mockObjectStoreURIExtractor.extractObjectStoreResourceLocation(any[String].asInstanceOf[ObjectStoreURI]))
          .thenReturn(EitherT.rightT(objectStoreResourceLocation))

        when(mockObjectStoreService.getObjectStoreFile(any[String].asInstanceOf[ObjectStoreResourceLocation])(any[HeaderCarrier], any[ExecutionContext]))
          .thenReturn(EitherT.rightT(Source.single(ByteString("this is test content"))))

        when(mockMessageTypeExtractor.extractFromBody(any())).thenReturn(EitherT.rightT[Future, MessageTypeExtractionError](MessageType.DeclarationData))

        when(
          mockPersistenceConnector.postObjectStoreUri(
            any[String].asInstanceOf[MovementId],
            any[String].asInstanceOf[MessageId],
            any[String].asInstanceOf[MessageType],
            any[String].asInstanceOf[ObjectStoreURI]
          )(any(), any())
        ).thenReturn(EitherT.fromEither(Left(MovementNotFound(MovementId("ABC")))))

        val request = fakeRequestLargeMessage(Json.toJson(successUpscanResponse), incomingLargeMessage)
        val result  = controller().incomingViaUpscan(movementId, messageId)(request)

        status(result) mustBe NOT_FOUND
    }

    "must return BAD_REQUEST when malformed json received from callback" in forAll(
      arbitraryMovementId.arbitrary,
      arbitraryMessageId.arbitrary
    ) {

      (movementId, messageId) =>
        when(
          mockUpscanResponseParser.parseAndLogUpscanResponse(
            any[String].asInstanceOf[JsValue]
          )
        ).thenReturn(EitherT.fromEither(Left(PresentationError.badRequestError("Unexpected Upscan callback response"))))

        val request = fakeRequestLargeMessage(Json.obj("reference" -> "abc"), incomingLargeMessage)
        val result  = controller().incomingViaUpscan(movementId, messageId)(request)

        status(result) mustBe BAD_REQUEST
    }

    "must return INTERNAL_SERVER_ERROR when uploading to object-store fails" in forAll(
      arbitraryUpscanResponse(true).arbitrary,
      arbitraryMovementId.arbitrary,
      arbitraryMessageId.arbitrary
    ) {
      (successUpscanResponse, movementId, messageId) =>
        when(
          mockObjectStoreService.storeIncoming(
            any[String].asInstanceOf[DownloadUrl],
            any[String].asInstanceOf[MovementId],
            any[String].asInstanceOf[MessageId]
          )(
            any(),
            any()
          )
        ).thenReturn(EitherT.leftT(ObjectStoreError.UnexpectedError(None)))

        val request = fakeRequestLargeMessage(Json.toJson(successUpscanResponse), incomingLargeMessage)

        val result = controller().incomingViaUpscan(movementId, messageId)(request)

        status(result) mustBe INTERNAL_SERVER_ERROR
    }

    "must return BAD_REQUEST when body seems to not contain an appropriate root tag" in forAll(
      arbitraryUpscanResponse(true).arbitrary,
      arbitraryMovementId.arbitrary,
      arbitraryMessageId.arbitrary,
      arbitraryObjectSummaryWithMd5.arbitrary,
      arbitraryObjectStoreResourceLocation.arbitrary
    ) {
      (successUpscanResponse, movementId, messageId, objectSummary, objectStoreResourceLocation) =>
        when(
          mockObjectStoreService.storeIncoming(
            any[String].asInstanceOf[DownloadUrl],
            any[String].asInstanceOf[MovementId],
            any[String].asInstanceOf[MessageId]
          )(
            any(),
            any()
          )
        ).thenReturn(EitherT.rightT(objectSummary))

        when(mockObjectStoreURIExtractor.extractObjectStoreResourceLocation(any[String].asInstanceOf[ObjectStoreURI]))
          .thenReturn(EitherT.rightT(objectStoreResourceLocation))

        when(mockObjectStoreService.getObjectStoreFile(any[String].asInstanceOf[ObjectStoreResourceLocation])(any[HeaderCarrier], any[ExecutionContext]))
          .thenReturn(EitherT.rightT(Source.single(ByteString("this is test content"))))

        when(mockMessageTypeExtractor.extractFromBody(any())).thenReturn(EitherT.leftT[Future, MessageType](MessageTypeExtractionError.UnableToExtractFromBody))

        val request = fakeRequestLargeMessage(Json.toJson(successUpscanResponse), incomingLargeMessage)
        val result  = controller().incomingViaUpscan(movementId, messageId)(request)

        status(result) mustBe BAD_REQUEST
    }

    "must return BAD_REQUEST when message type is invalid" in forAll(
      arbitraryUpscanResponse(true).arbitrary,
      arbitraryMovementId.arbitrary,
      arbitraryMessageId.arbitrary,
      arbitraryObjectSummaryWithMd5.arbitrary,
      arbitraryObjectStoreResourceLocation.arbitrary
    ) {
      (successUpscanResponse, movementId, messageId, objectSummary, objectStoreResourceLocation) =>
        when(
          mockObjectStoreService.storeIncoming(
            any[String].asInstanceOf[DownloadUrl],
            any[String].asInstanceOf[MovementId],
            any[String].asInstanceOf[MessageId]
          )(
            any(),
            any()
          )
        ).thenReturn(EitherT.rightT(objectSummary))

        when(mockObjectStoreURIExtractor.extractObjectStoreResourceLocation(any[String].asInstanceOf[ObjectStoreURI]))
          .thenReturn(EitherT.rightT(objectStoreResourceLocation))

        when(mockObjectStoreService.getObjectStoreFile(any[String].asInstanceOf[ObjectStoreResourceLocation])(any[HeaderCarrier], any[ExecutionContext]))
          .thenReturn(EitherT.rightT(Source.single(ByteString("this is test content"))))

        when(mockMessageTypeExtractor.extractFromBody(any()))
          .thenReturn(EitherT.leftT[Future, MessageType](MessageTypeExtractionError.InvalidMessageType("abcde")))

        val request = fakeRequestLargeMessage(Json.toJson(successUpscanResponse), incomingLargeMessage)
        val result  = controller().incomingViaUpscan(movementId, messageId)(request)

        status(result) mustBe BAD_REQUEST
    }

    "must return BAD_REQUEST when file not found on object store resource location" in forAll(
      arbitraryUpscanResponse(true).arbitrary,
      arbitraryMovementId.arbitrary,
      arbitraryMessageId.arbitrary,
      arbitraryObjectSummaryWithMd5.arbitrary,
      arbitraryObjectStoreResourceLocation.arbitrary
    ) {
      (successUpscanResponse, movementId, messageId, objectSummary, objectStoreResourceLocation) =>
        when(
          mockObjectStoreService.storeIncoming(
            any[String].asInstanceOf[DownloadUrl],
            any[String].asInstanceOf[MovementId],
            any[String].asInstanceOf[MessageId]
          )(
            any(),
            any()
          )
        ).thenReturn(EitherT.rightT(objectSummary))

        when(mockObjectStoreURIExtractor.extractObjectStoreResourceLocation(any[String].asInstanceOf[ObjectStoreURI]))
          .thenReturn(EitherT.rightT(objectStoreResourceLocation))

        when(mockObjectStoreService.getObjectStoreFile(any[String].asInstanceOf[ObjectStoreResourceLocation])(any[HeaderCarrier], any[ExecutionContext]))
          .thenReturn(EitherT.leftT(ObjectStoreError.FileNotFound(objectStoreResourceLocation.contextPath)))

        val request = fakeRequestLargeMessage(Json.toJson(successUpscanResponse), incomingLargeMessage)
        val result  = controller().incomingViaUpscan(movementId, messageId)(request)

        status(result) mustBe BAD_REQUEST
        contentAsJson(result) mustBe Json.obj(
          "code"    -> "BAD_REQUEST",
          "message" -> s"file not found at location: ${objectStoreResourceLocation.contextPath}"
        )
    }

    "must return INTERNAL_SERVER_ERROR when persistence service fails unexpected" in forAll(
      arbitraryUpscanResponse(true).arbitrary,
      arbitraryMovementId.arbitrary,
      arbitraryMessageId.arbitrary,
      arbitraryObjectSummaryWithMd5.arbitrary,
      arbitraryObjectStoreResourceLocation.arbitrary
    ) {
      (successUpscanResponse, movementId, messageId, objectSummary, objectStoreResourceLocation) =>
        when(
          mockObjectStoreService.storeIncoming(
            any[String].asInstanceOf[DownloadUrl],
            any[String].asInstanceOf[MovementId],
            any[String].asInstanceOf[MessageId]
          )(
            any(),
            any()
          )
        ).thenReturn(EitherT.rightT(objectSummary))

        when(mockObjectStoreURIExtractor.extractObjectStoreResourceLocation(any[String].asInstanceOf[ObjectStoreURI]))
          .thenReturn(EitherT.rightT(objectStoreResourceLocation))

        when(mockObjectStoreService.getObjectStoreFile(any[String].asInstanceOf[ObjectStoreResourceLocation])(any[HeaderCarrier], any[ExecutionContext]))
          .thenReturn(EitherT.rightT(Source.single(ByteString("this is test content"))))

        when(mockMessageTypeExtractor.extractFromBody(any())).thenReturn(EitherT.rightT[Future, MessageTypeExtractionError](MessageType.DeclarationData))

        when(
          mockPersistenceConnector.postObjectStoreUri(
            any[String].asInstanceOf[MovementId],
            any[String].asInstanceOf[MessageId],
            any[String].asInstanceOf[MessageType],
            any[String].asInstanceOf[ObjectStoreURI]
          )(any(), any())
        )
          .thenReturn(EitherT.fromEither(Left(Unexpected(None))))

        val request = fakeRequestLargeMessage(Json.toJson(successUpscanResponse), incomingLargeMessage)
        val result  = controller().incomingViaUpscan(movementId, messageId)(request)

        status(result) mustBe INTERNAL_SERVER_ERROR
    }
  }

  "POST SDES callback" - {

    "must return OK when status is successfully updated for SDES callback" in {
      val conversationId          = ConversationId(UUID.randomUUID())
      val (movementId, messageId) = conversationId.toMovementAndMessageId
      val sdesResponse            = arbitrarySdesResponse(conversationId).arbitrary.sample.get.copy(notification = SdesNotificationType.FileProcessed)

      val ppnsMessage = Json.toJson(
        Json.obj(
          "code" -> "SUCCESS",
          "message" ->
            s"The message ${messageId.value} for movement ${movementId.value} was successfully processed"
        )
      )

      when(
        mockPersistenceConnector.patchMessageStatus(
          MovementId(eqTo(movementId.value)),
          MessageId(eqTo(messageId.value)),
          eqTo(MessageUpdate(MessageStatus.Success))
        )(any[HeaderCarrier], any[ExecutionContext])
      )
        .thenReturn(EitherT.rightT(()))

      when(
        mockPushNotificationsConnector.postJSON(
          MovementId(eqTo(movementId.value)),
          MessageId(eqTo(messageId.value)),
          eqTo(ppnsMessage)
        )(any[HeaderCarrier], any[ExecutionContext])
      )
        .thenReturn(EitherT.rightT(()))

      val request = fakeRequestLargeMessage(Json.toJson(sdesResponse), sdesCallback)

      val result = controller().handleSdesResponse()(request)

      status(result) mustBe OK

      verify(mockPushNotificationsConnector, times(1)).postJSON(
        MovementId(eqTo(movementId.value)),
        MessageId(eqTo(messageId.value)),
        eqTo(ppnsMessage)
      )(
        any[HeaderCarrier],
        any[ExecutionContext]
      )

      verify(mockPersistenceConnector, times(1)).patchMessageStatus(
        MovementId(eqTo(movementId.value)),
        MessageId(eqTo(messageId.value)),
        eqTo(MessageUpdate(MessageStatus.Success))
      )(
        any[HeaderCarrier],
        any[ExecutionContext]
      )
    }

    "must return OK when status is updated successfully but push notification got failed for SDES callback" in {

      val conversationId          = ConversationId(UUID.randomUUID())
      val (movementId, messageId) = conversationId.toMovementAndMessageId
      val sdesResponse            = arbitrarySdesResponse(conversationId).arbitrary.sample.get.copy(notification = SdesNotificationType.FileProcessingFailure)

      val ppnsMessage = Json.obj(
        "code"    -> "INTERNAL_SERVER_ERROR",
        "message" -> "Internal server error"
      )

      when(
        mockPersistenceConnector.patchMessageStatus(
          MovementId(eqTo(movementId.value)),
          MessageId(eqTo(messageId.value)),
          eqTo(MessageUpdate(MessageStatus.Failed))
        )(any[HeaderCarrier], any[ExecutionContext])
      )
        .thenReturn(EitherT.rightT(()))
      when(
        mockPushNotificationsConnector
          .postJSON(MovementId(eqTo(movementId.value)), MessageId(eqTo(messageId.value)), eqTo(ppnsMessage))(
            any[HeaderCarrier],
            any[ExecutionContext]
          )
      )
        .thenReturn(EitherT.fromEither(Left(PushNotificationError.Unexpected(None))))
      val request = fakeRequestLargeMessage(Json.toJson(sdesResponse), sdesCallback)

      val result = controller().handleSdesResponse()(request)

      status(result) mustBe OK
      verify(mockPushNotificationsConnector, times(1)).postJSON(
        MovementId(eqTo(movementId.value)),
        MessageId(eqTo(messageId.value)),
        eqTo(ppnsMessage)
      )(
        any[HeaderCarrier],
        any[ExecutionContext]
      )

      verify(mockPersistenceConnector, times(1)).patchMessageStatus(
        MovementId(eqTo(movementId.value)),
        MessageId(eqTo(messageId.value)),
        eqTo(MessageUpdate(MessageStatus.Failed))
      )(
        any[HeaderCarrier],
        any[ExecutionContext]
      )
    }

    "must return INTERNAL_SERVER_ERROR when status is not updated for SDES callback" in {

      val conversationId          = ConversationId(UUID.randomUUID())
      val (movementId, messageId) = conversationId.toMovementAndMessageId
      val sdesResponse            = arbitrarySdesResponse(conversationId).arbitrary.sample.get
      val ppnsMessage = Json.obj(
        "code"    -> "INTERNAL_SERVER_ERROR",
        "message" -> "Internal server error"
      )
      val messageStatus = sdesResponse.notification match {
        case SdesNotificationType.FileProcessed         => MessageStatus.Success
        case SdesNotificationType.FileProcessingFailure => MessageStatus.Failed
      }

      when(
        mockPersistenceConnector.patchMessageStatus(
          MovementId(eqTo(movementId.value)),
          MessageId(eqTo(messageId.value)),
          eqTo(MessageUpdate(messageStatus))
        )(any(), any())
      )
        .thenReturn(EitherT.fromEither(Left(Unexpected())))

      when(
        mockPushNotificationsConnector
          .postJSON(MovementId(eqTo(movementId.value)), MessageId(eqTo(messageId.value)), eqTo(ppnsMessage))(
            any[HeaderCarrier],
            any[ExecutionContext]
          )
      ).thenReturn(EitherT.rightT(()))

      val request = fakeRequestLargeMessage(Json.toJson(sdesResponse), sdesCallback)

      val result = controller().handleSdesResponse()(request)

      status(result) mustBe INTERNAL_SERVER_ERROR

      verify(mockPushNotificationsConnector, times(1)).postJSON(
        MovementId(eqTo(movementId.value)),
        MessageId(eqTo(messageId.value)),
        eqTo(ppnsMessage)
      )(
        any[HeaderCarrier],
        any[ExecutionContext]
      )

      verify(mockPersistenceConnector, times(1)).patchMessageStatus(
        MovementId(eqTo(movementId.value)),
        MessageId(eqTo(messageId.value)),
        eqTo(MessageUpdate(messageStatus))
      )(
        any[HeaderCarrier],
        any[ExecutionContext]
      )
    }

    "must return BAD_REQUEST for SDES malformed callback" in {
      val request = fakeRequestLargeMessage(Json.toJson("reference" -> "abc"), sdesCallback)

      val result = controller().handleSdesResponse()(request)

      status(result) mustBe BAD_REQUEST
    }
  }

  "MessagesController object" - {
    "PresentationEitherTHelper" - {

      import MessagesController.PresentationEitherTHelper

      "for an EitherT with a Right, return the right" in forAll(Gen.option(Gen.oneOf(MessageType.values))) {
        messageTypeMaybe =>
          val incoming: EitherT[Future, RoutingError, Unit]                    = EitherT(Future.successful[Either[RoutingError, Unit]](Right((): Unit)))
          val expected: Either[(PresentationError, Option[MessageType]), Unit] = Right((): Unit)

          whenReady(incoming.asPresentationWithMessageType(messageTypeMaybe).value) {
            _ mustBe expected
          }
      }

      "for an EitherT with a Left, return the left with the appropriate message type, if any" in forAll(Gen.option(Gen.oneOf(MessageType.values))) {
        messageTypeMaybe =>
          val incoming: EitherT[Future, RoutingError, Unit] =
            EitherT(Future.successful[Either[RoutingError, Unit]](Left(RoutingError.Unexpected("error", None))))
          val expected: Either[(PresentationError, Option[MessageType]), Unit] = Left((PresentationError.internalServiceError(), messageTypeMaybe))

          whenReady(incoming.asPresentationWithMessageType(messageTypeMaybe).value) {
            _ mustBe expected
          }
      }

    }
  }
}
