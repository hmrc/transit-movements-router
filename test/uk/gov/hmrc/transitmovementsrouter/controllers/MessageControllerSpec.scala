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
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.MockitoSugar.reset
import org.mockito.MockitoSugar.when
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Gen
import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers.convertToAnyMustWrapper
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar.mock
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import play.api.Logger
import play.api.http.DefaultHttpErrorHandler
import play.api.http.HeaderNames
import play.api.http.HttpErrorConfig
import play.api.http.MimeTypes
import play.api.http.Status._
import play.api.libs.Files
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
import uk.gov.hmrc.internalauth.client.IAAction
import uk.gov.hmrc.internalauth.client.Predicate
import uk.gov.hmrc.internalauth.client.Resource
import uk.gov.hmrc.internalauth.client.ResourceLocation
import uk.gov.hmrc.internalauth.client.ResourceType
import uk.gov.hmrc.objectstore.client.ObjectSummaryWithMd5
import uk.gov.hmrc.transitmovementsrouter.base.StreamTestHelpers.createStream
import uk.gov.hmrc.transitmovementsrouter.base.TestActorSystem
import uk.gov.hmrc.transitmovementsrouter.base.TestSourceProvider
import uk.gov.hmrc.transitmovementsrouter.config.AppConfig
import uk.gov.hmrc.transitmovementsrouter.connectors.PersistenceConnector
import uk.gov.hmrc.transitmovementsrouter.connectors.PushNotificationsConnector
import uk.gov.hmrc.transitmovementsrouter.connectors.UpscanConnector
import uk.gov.hmrc.transitmovementsrouter.controllers.actions.AuthenticateEISToken
import uk.gov.hmrc.transitmovementsrouter.controllers.actions.InternalAuthActionProvider
import uk.gov.hmrc.transitmovementsrouter.controllers.errors.PresentationError
import uk.gov.hmrc.transitmovementsrouter.fakes.actions.FakeXmlTransformer
import uk.gov.hmrc.transitmovementsrouter.generators.TestModelGenerators
import uk.gov.hmrc.transitmovementsrouter.models.AuditType.NCTSRequestedMissingMovement
import uk.gov.hmrc.transitmovementsrouter.models.AuditType.NCTSToTraderSubmissionSuccessful
import uk.gov.hmrc.transitmovementsrouter.models.MessageType.DeclarationAmendment
import uk.gov.hmrc.transitmovementsrouter.models.MessageType.GoodsReleaseNotification
import uk.gov.hmrc.transitmovementsrouter.models.MessageType.MrnAllocated
import uk.gov.hmrc.transitmovementsrouter.models._
import uk.gov.hmrc.transitmovementsrouter.models.errors.PersistenceError.MovementNotFound
import uk.gov.hmrc.transitmovementsrouter.models.errors.PersistenceError.Unexpected
import uk.gov.hmrc.transitmovementsrouter.models.errors._
import uk.gov.hmrc.transitmovementsrouter.models.requests.MessageUpdate
import uk.gov.hmrc.transitmovementsrouter.models.responses.UpscanResponse
import uk.gov.hmrc.transitmovementsrouter.models.responses.UpscanResponse.DownloadUrl
import uk.gov.hmrc.transitmovementsrouter.models.responses.UpscanSuccessResponse
import uk.gov.hmrc.transitmovementsrouter.models.sdes.SdesNotificationType
import uk.gov.hmrc.transitmovementsrouter.services._

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
    with TestSourceProvider
    with ScalaFutures {

  val eori: EoriNumber           = EoriNumber("eori")
  val movementType: MovementType = MovementType("departure")
  val movementId: MovementId     = MovementId("abcdef1234567890")
  val messageId: MessageId       = MessageId("0987654321fedcba")

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

  val mockRoutingService: RoutingService                             = mock[RoutingService]
  val mockPersistenceConnector: PersistenceConnector                 = mock[PersistenceConnector]
  val mockPushNotificationsConnector: PushNotificationsConnector     = mock[PushNotificationsConnector]
  val mockUpscanResponseParser: UpscanResponseParser                 = mock[UpscanResponseParser]
  val mockObjectStoreService: ObjectStoreService                     = mock[ObjectStoreService]
  val mockCustomOfficeExtractorService: CustomOfficeExtractorService = mock[CustomOfficeExtractorService]
  val mockSDESService: SDESService                                   = mock[SDESService]
  val mockSdesResponseParser: SdesResponseParser                     = mock[SdesResponseParser]
  val mockUpscanConnector: UpscanConnector                           = mock[UpscanConnector]
  val mockAuditingService: AuditingService                           = mock[AuditingService]

  implicit val temporaryFileCreator: Files.SingletonTemporaryFileCreator.type = SingletonTemporaryFileCreator

  val errorHandler = new DefaultHttpErrorHandler(HttpErrorConfig(showDevErrors = false, None), None, None)

  val controllerComponentWithTempFile: ControllerComponents =
    stubControllerComponents(playBodyParsers = PlayBodyParsers(SingletonTemporaryFileCreator, errorHandler)(materializer))

  object FakeAuthenticateEISToken extends AuthenticateEISToken {
    override protected def filter[A](request: Request[A]): Future[Option[Result]] = Future.successful(None)

    override def parser: BodyParser[AnyContent] = stubControllerComponents().parsers.defaultBodyParser

    override protected def executionContext: ExecutionContext = scala.concurrent.ExecutionContext.global
  }

  val mockInternalAuthActionProvider: InternalAuthActionProvider = mock[InternalAuthActionProvider]
  val mockMessageTypeExtractor: MessageTypeExtractor             = mock[MessageTypeExtractor]
  val mockStatusMonitoringService: ServiceMonitoringService      = mock[ServiceMonitoringService]
  val config: AppConfig                                          = mock[AppConfig]

  def controller(eisMessageTransformer: EISMessageTransformers = new FakeXmlTransformer(trimmedXml)): MessagesController =
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
      mockInternalAuthActionProvider,
      mockStatusMonitoringService,
      mockAuditingService,
      config
    ) {
      // suppress logging
      override protected val logger: Logger = mock[Logger]
    }

  def source: Source[ByteString, _] = createStream(cc015cOfficeOfDepartureGB)

  val outgoing: String             = routes.MessagesController.outgoing(eori, movementType, movementId, messageId).url
  val incoming: String             = routes.MessagesController.incomingViaEIS(ConversationId(movementId, messageId)).url
  val incomingLargeMessage: String = routes.MessagesController.incomingViaUpscan(movementId, messageId).url
  val sdesCallback: String         = routes.MessagesController.handleSdesResponse().url

  def fakeRequest(
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

  def fakeRequestLargeMessage(
    body: JsValue,
    url: String
  ): Request[JsValue] =
    FakeRequest(
      method = POST,
      uri = url,
      headers = FakeHeaders(Seq(HeaderNames.CONTENT_TYPE -> MimeTypes.JSON)),
      body = body
    )

  private def resetAuthActionAndStatusMonitoring() = {
    reset(mockStatusMonitoringService)
    reset(mockInternalAuthActionProvider)
    when(
      mockInternalAuthActionProvider(
        eqTo(Predicate.Permission(Resource(ResourceType("transit-movements-router"), ResourceLocation("message")), IAAction("WRITE")))
      )(any())
    )
      .thenReturn(DefaultActionBuilder.apply(stubControllerComponents().parsers.defaultBodyParser))
  }

  override def beforeEach(): Unit = {
    reset(mockRoutingService)
    reset(mockMessageTypeExtractor)
    reset(mockPersistenceConnector)
    reset(mockObjectStoreService)
    reset(mockCustomOfficeExtractorService)
    reset(mockSDESService)
    reset(mockPushNotificationsConnector)
    reset(mockUpscanConnector)
    reset(mockAuditingService)
    reset(config)
    when(config.logIncoming).thenReturn(true)
    when(config.eisSizeLimit).thenReturn(5000000) // TODO: vary per test
    reset(mockAuditingService)
    resetAuthActionAndStatusMonitoring()
    super.afterEach()
  }

  lazy val submitDeclarationEither: EitherT[Future, RoutingError, Unit] =
    EitherT.rightT(())

  def messageTypeHeader(messageType: MessageType): FakeHeaders = FakeHeaders(Seq(("X-Message-Type", messageType.code)))
  lazy val messageTypeHeaderDepartureDeclaration: FakeHeaders  = messageTypeHeader(MessageType.DeclarationData)

  "POST outgoing" - {
    "must return CREATED when declaration is submitted successfully via the EIS route" in forAll(
      arbitrary[CustomsOffice],
      arbitrary[RequestMessageType]
    ) {
      (customsOffice, messageType) =>
        resetAuthActionAndStatusMonitoring()
        when(
          mockRoutingService.submitMessage(
            any[String].asInstanceOf[MovementType],
            any[String].asInstanceOf[MovementId],
            any[String].asInstanceOf[MessageId],
            any[Source[ByteString, _]],
            any[String].asInstanceOf[CustomsOffice]
          )(any[HeaderCarrier], any[ExecutionContext])
        ).thenReturn(submitDeclarationEither)

        when(mockMessageTypeExtractor.extractFromHeaders(any())).thenReturn(EitherT.rightT[Future, MessageTypeExtractionError](messageType))

        when(mockCustomOfficeExtractorService.extractCustomOffice(any(), any()))
          .thenReturn(EitherT.rightT[Future, CustomOfficeExtractorError](customsOffice))

        val result =
          controller().outgoing(eori, movementType, movementId, messageId)(fakeRequest(cc015cOfficeOfDepartureGB, outgoing, messageTypeHeader(messageType)))

        status(result) mustBe CREATED

        verify(mockInternalAuthActionProvider, times(1)).apply(
          eqTo(Predicate.Permission(Resource(ResourceType("transit-movements-router"), ResourceLocation("message")), IAAction("WRITE")))
        )(any())

        verify(mockStatusMonitoringService, times(1)).outgoing(
          MovementId(eqTo(movementId.value)),
          MessageId(eqTo(messageId.value)),
          eqTo(messageType),
          eqTo(customsOffice)
        )(any(), any())
    }

    "must return ACCEPTED when declaration is submitted successfully via the SDES route" in forAll(
      arbitrary[EoriNumber],
      arbitrary[MovementType],
      arbitrary[MovementId],
      arbitrary[MessageId],
      arbitrary[ObjectSummaryWithMd5],
      arbitrary[RequestMessageType]
    ) {
      (eori, movementType, movementId, messageId, summary, messageType) =>
        resetAuthActionAndStatusMonitoring()
        val expectedConversationId = ConversationId(movementId, messageId)

        when(config.eisSizeLimit).thenReturn(-1)

        when(mockMessageTypeExtractor.extractFromHeaders(any())).thenReturn(EitherT.rightT[Future, MessageTypeExtractionError](messageType))

        when(mockCustomOfficeExtractorService.extractCustomOffice(any(), eqTo(messageType)))
          .thenReturn(EitherT.rightT[Future, CustomOfficeExtractorError](CustomsOffice("GB1234567")))

        when(
          mockObjectStoreService
            .storeOutgoing(ConversationId(eqTo(expectedConversationId.value)), any[Source[ByteString, _]])(any[HeaderCarrier], any[ExecutionContext])
        )
          .thenReturn(EitherT.rightT(summary))

        when(mockSDESService.send(MovementId(eqTo(movementId.value)), MessageId(eqTo(messageId.value)), eqTo(summary))(any(), any()))
          .thenReturn(EitherT.rightT((): Unit))

        val result = controller().outgoing(eori, movementType, movementId, messageId)(
          fakeRequest(cc015cOfficeOfDepartureGB, outgoing, messageTypeHeaderDepartureDeclaration)
        )

        status(result) mustBe ACCEPTED

        verify(mockRoutingService, times(0)).submitMessage(
          any[String].asInstanceOf[MovementType],
          any[String].asInstanceOf[MovementId],
          any[String].asInstanceOf[MessageId],
          any[Source[ByteString, _]],
          any[String].asInstanceOf[CustomsOffice]
        )(any[HeaderCarrier], any[ExecutionContext])

        verify(mockSDESService, times(1)).send(
          MovementId(eqTo(movementId.value)),
          MessageId(eqTo(messageId.value)),
          eqTo(summary)
        )(any[ExecutionContext], any[HeaderCarrier])

        verify(mockInternalAuthActionProvider, times(1)).apply(
          eqTo(Predicate.Permission(Resource(ResourceType("transit-movements-router"), ResourceLocation("message")), IAAction("WRITE")))
        )(any())

        verifyNoInteractions(mockStatusMonitoringService)
    }

    "must return BAD_REQUEST when declaration submission fails" - {

      "returns INVALID_OFFICE when an invalid custom office supplied in payload" in forAll(Gen.alphaNumStr, Gen.alphaStr) {
        (office, field) =>
          resetAuthActionAndStatusMonitoring()
          when(mockCustomOfficeExtractorService.extractCustomOffice(any(), any()))
            .thenReturn(
              EitherT[Future, CustomOfficeExtractorError, CustomsOffice](
                Future.successful(Left(CustomOfficeExtractorError.UnrecognisedOffice("office", CustomsOffice(office), field)))
              )
            )

          when(mockMessageTypeExtractor.extractFromHeaders(any())).thenReturn(EitherT.rightT[Future, MessageTypeExtractionError](MessageType.DeclarationData))

          val result = controller().outgoing(eori, movementType, movementId, messageId)(
            fakeRequest(cc015cOfficeOfDepartureGB, outgoing, messageTypeHeaderDepartureDeclaration)
          )

          status(result) mustBe BAD_REQUEST
          contentAsJson(result) mustBe Json.obj(
            "code"    -> "INVALID_OFFICE",
            "message" -> "office",
            "office"  -> office,
            "field"   -> field
          )

          verify(mockInternalAuthActionProvider, times(1)).apply(
            eqTo(Predicate.Permission(Resource(ResourceType("transit-movements-router"), ResourceLocation("message")), IAAction("WRITE")))
          )(any())
      }

      "returns message to indicate element not found" in {

        when(mockCustomOfficeExtractorService.extractCustomOffice(any(), any()))
          .thenReturn(
            EitherT[Future, CustomOfficeExtractorError, CustomsOffice](Future.successful(Left(CustomOfficeExtractorError.NoElementFound("messageSender"))))
          )

        when(mockMessageTypeExtractor.extractFromHeaders(any())).thenReturn(EitherT.rightT[Future, MessageTypeExtractionError](MessageType.DeclarationData))

        val result = controller().outgoing(eori, movementType, movementId, messageId)(
          fakeRequest(cc015cOfficeOfDepartureGB, outgoing, messageTypeHeaderDepartureDeclaration)
        )

        status(result) mustBe BAD_REQUEST
        contentAsJson(result) mustBe Json.obj(
          "code"    -> "BAD_REQUEST",
          "message" -> "Element messageSender not found"
        )

        verify(mockInternalAuthActionProvider, times(1)).apply(
          eqTo(Predicate.Permission(Resource(ResourceType("transit-movements-router"), ResourceLocation("message")), IAAction("WRITE")))
        )(any())
      }

      "returns message to indicate too many elements" in {

        when(mockCustomOfficeExtractorService.extractCustomOffice(any(), any()))
          .thenReturn(
            EitherT[Future, CustomOfficeExtractorError, CustomsOffice](Future.successful(Left(CustomOfficeExtractorError.TooManyElementsFound("eori"))))
          )

        when(mockMessageTypeExtractor.extractFromHeaders(any())).thenReturn(EitherT.rightT[Future, MessageTypeExtractionError](MessageType.DeclarationData))

        val result = controller().outgoing(eori, movementType, movementId, messageId)(
          fakeRequest(cc015cOfficeOfDepartureGB, outgoing, messageTypeHeaderDepartureDeclaration)
        )

        status(result) mustBe BAD_REQUEST
        contentAsJson(result) mustBe Json.obj(
          "code"    -> "BAD_REQUEST",
          "message" -> "Found too many elements of type eori"
        )

        verify(mockInternalAuthActionProvider, times(1)).apply(
          eqTo(Predicate.Permission(Resource(ResourceType("transit-movements-router"), ResourceLocation("message")), IAAction("WRITE")))
        )(any())
      }

      "returns message to inform that the X-Message-Type header value IE140 is invalid" in {

        when(mockMessageTypeExtractor.extractFromHeaders(any()))
          .thenReturn(EitherT.leftT[Future, MessageType](MessageTypeExtractionError.InvalidMessageType("IE140")))

        val result = controller().outgoing(eori, movementType, movementId, messageId)(
          fakeRequest(cc015cOfficeOfDepartureGB, outgoing, FakeHeaders(Seq(("X-Message-Type", "IE140"))))
        )

        status(result) mustBe BAD_REQUEST
        contentAsJson(result) mustBe Json.obj(
          "code"    -> "BAD_REQUEST",
          "message" -> "Invalid message type: IE140"
        )

        verify(mockInternalAuthActionProvider, times(1)).apply(
          eqTo(Predicate.Permission(Resource(ResourceType("transit-movements-router"), ResourceLocation("message")), IAAction("WRITE")))
        )(any())
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

        verify(mockInternalAuthActionProvider, times(1)).apply(
          eqTo(Predicate.Permission(Resource(ResourceType("transit-movements-router"), ResourceLocation("message")), IAAction("WRITE")))
        )(any())
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

        verify(mockInternalAuthActionProvider, times(1)).apply(
          eqTo(Predicate.Permission(Resource(ResourceType("transit-movements-router"), ResourceLocation("message")), IAAction("WRITE")))
        )(any())
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

        verify(mockInternalAuthActionProvider, times(1)).apply(
          eqTo(Predicate.Permission(Resource(ResourceType("transit-movements-router"), ResourceLocation("message")), IAAction("WRITE")))
        )(any())
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

      val result = controller().outgoing(eori, movementType, movementId, messageId)(
        fakeRequest(cc015cOfficeOfDepartureGB, outgoing, messageTypeHeaderDepartureDeclaration)
      )

      status(result) mustBe INTERNAL_SERVER_ERROR
      contentAsJson(result) mustBe Json.obj(
        "code"    -> "INTERNAL_SERVER_ERROR",
        "message" -> "Internal server error"
      )

      verify(mockInternalAuthActionProvider, times(1)).apply(
        eqTo(Predicate.Permission(Resource(ResourceType("transit-movements-router"), ResourceLocation("message")), IAAction("WRITE")))
      )(any())
    }

  }

  "POST incoming from EIS" - {
    "must return CREATED when message is successfully forwarded" in forAll(arbitrary[MovementId], arbitrary[MessageId], arbitrary[ResponseMessageType]) {
      (movementId, messageId, messageType) =>
        when(mockMessageTypeExtractor.extract(any(), any())).thenReturn(EitherT.rightT[Future, MessageTypeExtractionError](messageType))

        when(mockPersistenceConnector.postBody(MovementId(eqTo(movementId.value)), MessageId(eqTo(messageId.value)), eqTo(messageType), any())(any(), any()))
          .thenReturn(EitherT.fromEither(Right(PersistenceResponse(messageId))))

        when(
          mockPushNotificationsConnector
            .postMessageReceived(MovementId(eqTo(movementId.value)), MessageId(eqTo(messageId.value)), eqTo(messageType), any[Source[ByteString, _]])(
              any[HeaderCarrier],
              any[ExecutionContext]
            )
        ).thenReturn(EitherT.rightT(()))

        when(
          mockAuditingService.auditStatusEvent(
            eqTo(NCTSRequestedMissingMovement),
            eqTo(Some(Json.toJson(PresentationError.notFoundError(s"Movement ${movementId.value} not found")))),
            eqTo(Some(movementId)),
            eqTo(None),
            eqTo(None),
            eqTo(Some(messageType.movementType)),
            eqTo(Some(messageType))
          )(any[HeaderCarrier], any[ExecutionContext])
        ).thenReturn(Future.successful(()))

        when(
          mockAuditingService.auditStatusEvent(
            eqTo(NCTSToTraderSubmissionSuccessful),
            eqTo(None),
            eqTo(Some(movementId)),
            eqTo(Some(messageId)),
            eqTo(None),
            eqTo(Some(messageType.movementType)),
            eqTo(Some(messageType))
          )(any[HeaderCarrier], any[ExecutionContext])
        ).thenReturn(Future.successful(()))

        val request = fakeRequest(incomingXml, incoming)
          .withHeaders(FakeHeaders().add("X-Message-Type" -> GoodsReleaseNotification.code))

        val result = controller().incomingViaEIS(ConversationId(movementId, messageId))(request)

        status(result) mustBe CREATED
        header("X-Message-Id", result) mustBe Some(messageId.value)

        verifyNoInteractions(mockInternalAuthActionProvider)
        verify(mockStatusMonitoringService, times(1)).incoming(
          MovementId(eqTo(movementId.value)),
          MessageId(eqTo(messageId.value)),
          eqTo(messageType)
        )(any[HeaderCarrier](), any[ExecutionContext]())

        verify(mockAuditingService, times(1)).auditMessageEvent(
          eqTo(messageType.auditType.get),
          eqTo(MimeTypes.XML),
          any(),
          any(),
          eqTo(Some(movementId)),
          eqTo(Some(messageId)),
          eqTo(None),
          eqTo(Some(messageType.movementType)),
          eqTo(Some(messageType))
        )(any[HeaderCarrier](), any[ExecutionContext]())
        verify(mockAuditingService, times(1)).auditStatusEvent(
          eqTo(NCTSToTraderSubmissionSuccessful),
          eqTo(None),
          eqTo(Some(movementId)),
          eqTo(Some(messageId)),
          eqTo(None),
          eqTo(Some(messageType.movementType)),
          eqTo(Some(messageType))
        )(any[HeaderCarrier], any[ExecutionContext])

        verify(mockAuditingService, times(0)).auditStatusEvent(
          eqTo(NCTSRequestedMissingMovement),
          eqTo(Some(Json.toJson(PresentationError.notFoundError(s"Movement ${movementId.value} not found")))),
          eqTo(Some(movementId)),
          eqTo(None),
          eqTo(None),
          eqTo(Some(messageType.movementType)),
          eqTo(Some(messageType))
        )(any[HeaderCarrier], any[ExecutionContext])
    }
    "must return BAD_REQUEST when the X-Message-Type header is missing or body seems to not contain an appropriate root tag" in {

      when(mockMessageTypeExtractor.extract(any(), any())).thenReturn(EitherT.leftT[Future, MessageType](MessageTypeExtractionError.UnableToExtractFromBody))
      val result = controller().incomingViaEIS(ConversationId(movementId, messageId))(fakeRequest(incomingXml, incoming))

      status(result) mustBe BAD_REQUEST
      verifyNoInteractions(mockInternalAuthActionProvider)
      verifyNoInteractions(mockStatusMonitoringService)
      verifyNoInteractions(mockAuditingService)
    }

    "must return BAD_REQUEST when message type is invalid" in {

      val request = fakeRequest(incomingXml, incoming)
        .withHeaders(FakeHeaders().add("X-Message-Type" -> "abcdef"))
      when(mockMessageTypeExtractor.extract(any(), any()))
        .thenReturn(EitherT.leftT[Future, MessageType](MessageTypeExtractionError.InvalidMessageType("abcde")))

      val result = controller().incomingViaEIS(ConversationId(movementId, messageId))(request)

      status(result) mustBe BAD_REQUEST
      verifyNoInteractions(mockInternalAuthActionProvider)
      verifyNoInteractions(mockStatusMonitoringService)
      verifyNoInteractions(mockAuditingService)
    }

    "must return BAD_REQUEST when message type is not a response message" in { //TODO: or should this be INTERNAL_SERVER_ERROR ?

      val request = fakeRequest(incomingXml, incoming)
        .withHeaders(FakeHeaders().add("X-Message-Type" -> "IE015"))

      when(mockMessageTypeExtractor.extract(any(), any()))
        .thenReturn(EitherT.rightT[Future, MessageTypeExtractionError](MessageType.DeclarationData))

      val result = controller().incomingViaEIS(ConversationId(movementId, messageId))(request)

      status(result) mustBe BAD_REQUEST
      verifyNoInteractions(mockInternalAuthActionProvider)
      verifyNoInteractions(mockStatusMonitoringService)
      verifyNoInteractions(mockAuditingService)
    }

    "must return NOT_FOUND when target movement is invalid or archived" in {

      when(mockPersistenceConnector.postBody(any[String].asInstanceOf[MovementId], any[String].asInstanceOf[MessageId], any(), any())(any(), any()))
        .thenReturn(EitherT.fromEither(Left(MovementNotFound(MovementId("abcdef1234567890")))))

      when(
        mockAuditingService.auditStatusEvent(
          eqTo(NCTSRequestedMissingMovement),
          eqTo(Some(Json.toJson(PresentationError.notFoundError(s"Movement ${movementId.value} not found")))),
          eqTo(Some(movementId)),
          eqTo(None),
          eqTo(None),
          eqTo(Some(MessageType.MrnAllocated.movementType)),
          eqTo(Some(MessageType.MrnAllocated))
        )(any[HeaderCarrier], any[ExecutionContext])
      ).thenReturn(Future.successful(()))

      when(
        mockAuditingService.auditStatusEvent(
          eqTo(NCTSToTraderSubmissionSuccessful),
          eqTo(None),
          eqTo(Some(movementId)),
          eqTo(Some(messageId)),
          eqTo(None),
          eqTo(Some(MessageType.MrnAllocated.movementType)),
          eqTo(Some(MessageType.MrnAllocated))
        )(any[HeaderCarrier], any[ExecutionContext])
      ).thenReturn(Future.successful(()))

      when(mockMessageTypeExtractor.extract(any(), any())).thenReturn(EitherT.rightT[Future, MessageTypeExtractionError](MessageType.MrnAllocated))

      val request = fakeRequest(incomingXml, incoming)
        .withHeaders(FakeHeaders().add("X-Message-Type" -> DeclarationAmendment.code))

      val result = controller().incomingViaEIS(ConversationId(movementId, messageId))(request)

      status(result) mustBe NOT_FOUND
      verifyNoInteractions(mockInternalAuthActionProvider)
      verify(mockAuditingService, times(0)).auditStatusEvent(
        eqTo(NCTSToTraderSubmissionSuccessful),
        eqTo(None),
        eqTo(Some(movementId)),
        eqTo(Some(messageId)),
        eqTo(None),
        eqTo(Some(MessageType.MrnAllocated.movementType)),
        eqTo(Some(MessageType.MrnAllocated))
      )(any[HeaderCarrier], any[ExecutionContext])
      verify(mockAuditingService, times(1)).auditStatusEvent(
        eqTo(NCTSRequestedMissingMovement),
        eqTo(Some(Json.toJson(PresentationError.notFoundError(s"Movement ${movementId.value} not found")))),
        eqTo(Some(movementId)),
        eqTo(None),
        eqTo(None),
        eqTo(Some(MessageType.MrnAllocated.movementType)),
        eqTo(Some(MessageType.MrnAllocated))
      )(any[HeaderCarrier], any[ExecutionContext])

      verify(mockAuditingService, times(0)).auditMessageEvent(
        eqTo(MessageType.MrnAllocated.auditType.get),
        eqTo(MimeTypes.XML),
        any(),
        any(),
        eqTo(Some(movementId)),
        eqTo(Some(messageId)),
        eqTo(None),
        eqTo(Some(MessageType.MrnAllocated.movementType)),
        eqTo(Some(MessageType.MrnAllocated))
      )(any[HeaderCarrier](), any[ExecutionContext]())
    }

    "must return INTERNAL_SERVER_ERROR when persistence service fails unexpected" in {

      when(mockPersistenceConnector.postBody(any[String].asInstanceOf[MovementId], any[String].asInstanceOf[MessageId], any(), any())(any(), any()))
        .thenReturn(EitherT.fromEither(Left(Unexpected(None))))

      when(
        mockAuditingService.auditStatusEvent(
          eqTo(NCTSRequestedMissingMovement),
          eqTo(Some(Json.toJson(PresentationError.notFoundError(s"Movement ${movementId.value} not found")))),
          eqTo(Some(movementId)),
          eqTo(None),
          eqTo(None),
          eqTo(Some(MessageType.MrnAllocated.movementType)),
          eqTo(Some(MessageType.DeclarationAmendment))
        )(any[HeaderCarrier], any[ExecutionContext])
      ).thenReturn(Future.successful(()))

      when(
        mockAuditingService.auditStatusEvent(
          eqTo(NCTSToTraderSubmissionSuccessful),
          eqTo(None),
          eqTo(Some(movementId)),
          eqTo(Some(messageId)),
          eqTo(None),
          eqTo(Some(MessageType.MrnAllocated.movementType)),
          eqTo(Some(MessageType.DeclarationAmendment))
        )(any[HeaderCarrier], any[ExecutionContext])
      ).thenReturn(Future.successful(()))

      when(mockMessageTypeExtractor.extract(any(), any())).thenReturn(EitherT.rightT[Future, MessageTypeExtractionError](MessageType.MrnAllocated))

      val request = fakeRequest(incomingXml, incoming)
        .withHeaders(FakeHeaders().add("X-Message-Type" -> MrnAllocated.code))

      val result = controller().incomingViaEIS(ConversationId(movementId, messageId))(request)

      status(result) mustBe INTERNAL_SERVER_ERROR
      verifyNoInteractions(mockInternalAuthActionProvider)
      verify(mockStatusMonitoringService, times(1)).incoming(
        MovementId(eqTo(movementId.value)),
        MessageId(eqTo(messageId.value)),
        eqTo(MrnAllocated)
      )(any(), any())
      verifyNoInteractions(mockAuditingService)
    }
    verify(mockAuditingService, times(0)).auditStatusEvent(
      eqTo(NCTSToTraderSubmissionSuccessful),
      eqTo(None),
      eqTo(Some(movementId)),
      eqTo(Some(messageId)),
      eqTo(None),
      eqTo(Some(MessageType.MrnAllocated.movementType)),
      eqTo(Some(MessageType.MrnAllocated))
    )(any[HeaderCarrier], any[ExecutionContext])
    verify(mockAuditingService, times(0)).auditStatusEvent(
      eqTo(NCTSRequestedMissingMovement),
      eqTo(Some(Json.toJson(PresentationError.notFoundError(s"Movement ${movementId.value} not found")))),
      eqTo(Some(movementId)),
      eqTo(None),
      eqTo(None),
      eqTo(Some(MessageType.MrnAllocated.movementType)),
      eqTo(Some(MessageType.MrnAllocated))
    )(any[HeaderCarrier], any[ExecutionContext])
  }

  "POST incoming from Upscan" - {

    "must return CREATED when message is successfully forwarded" in forAll(
      arbitrary[UpscanSuccessResponse],
      arbitraryMovementId.arbitrary,
      arbitraryMessageId.arbitrary,
      arbitrary[MessageType]
    ) {
      (successUpscanResponse, movementId, messageId, messageType) =>
        val source: Source[ByteString, _] = singleUseStringSource("abc")

        when(mockUpscanConnector.streamFile(DownloadUrl(eqTo(successUpscanResponse.downloadUrl.value)))(any(), any(), any()))
          .thenReturn(EitherT.rightT(source))

        when(mockMessageTypeExtractor.extractFromBody(any())).thenReturn(EitherT.rightT[Future, MessageTypeExtractionError](messageType))

        when(mockPersistenceConnector.postBody(MovementId(eqTo(movementId.value)), MessageId(eqTo(messageId.value)), eqTo(messageType), any())(any(), any()))
          .thenReturn(EitherT.fromEither(Right(PersistenceResponse(messageId))))

        when(
          mockPushNotificationsConnector
            .postMessageReceived(MovementId(eqTo(movementId.value)), MessageId(eqTo(messageId.value)), eqTo(messageType), any[Source[ByteString, _]])(
              any(),
              any()
            )
        ).thenReturn(EitherT.rightT(()))

        val request = fakeRequestLargeMessage(Json.toJson[UpscanResponse](successUpscanResponse), incomingLargeMessage)

        val result = controller().incomingViaUpscan(movementId, messageId)(request)

        status(result) mustBe CREATED
        header("X-Message-Id", result) mustBe Some(messageId.value)
        verifyNoInteractions(mockInternalAuthActionProvider)

        verify(mockStatusMonitoringService, times(1)).incoming(
          MovementId(eqTo(movementId.value)),
          MessageId(eqTo(messageId.value)),
          eqTo(messageType)
        )(any(), any())
    }

    "must return NOT_FOUND when target movement is invalid or archived" in forAll(
      arbitrary[UpscanSuccessResponse],
      arbitraryMovementId.arbitrary,
      arbitraryMessageId.arbitrary,
      arbitrary[MessageType]
    ) {
      (successUpscanResponse, movementId, messageId, messageType) =>
        val source: Source[ByteString, _] = singleUseStringSource("abc")

        when(mockUpscanConnector.streamFile(DownloadUrl(eqTo(successUpscanResponse.downloadUrl.value)))(any(), any(), any()))
          .thenReturn(EitherT.rightT(source))

        when(mockMessageTypeExtractor.extractFromBody(any())).thenReturn(EitherT.rightT[Future, MessageTypeExtractionError](messageType))

        when(mockPersistenceConnector.postBody(MovementId(eqTo(movementId.value)), MessageId(eqTo(messageId.value)), eqTo(messageType), any())(any(), any()))
          .thenReturn(EitherT.fromEither(Left(MovementNotFound(movementId))))

        val request = fakeRequestLargeMessage(Json.toJson[UpscanResponse](successUpscanResponse), incomingLargeMessage)
        val result  = controller().incomingViaUpscan(movementId, messageId)(request)

        status(result) mustBe NOT_FOUND

        verifyNoInteractions(mockPushNotificationsConnector)
        verifyNoInteractions(mockInternalAuthActionProvider)
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
        verifyNoInteractions(mockUpscanConnector)
        verifyNoInteractions(mockMessageTypeExtractor)
        verifyNoInteractions(mockPersistenceConnector)
        verifyNoInteractions(mockPushNotificationsConnector)
        verifyNoInteractions(mockInternalAuthActionProvider)
    }

    "must return BAD_REQUEST when body seems to not contain an appropriate root tag" in forAll(
      arbitrary[UpscanSuccessResponse],
      arbitraryMovementId.arbitrary,
      arbitraryMessageId.arbitrary
    ) {
      (successUpscanResponse, movementId, messageId) =>
        val source: Source[ByteString, _] = singleUseStringSource("abc")

        when(mockUpscanConnector.streamFile(DownloadUrl(eqTo(successUpscanResponse.downloadUrl.value)))(any(), any(), any()))
          .thenReturn(EitherT.rightT(source))

        when(mockMessageTypeExtractor.extractFromBody(any())).thenReturn(EitherT.leftT[Future, MessageType](MessageTypeExtractionError.UnableToExtractFromBody))

        val request = fakeRequestLargeMessage(Json.toJson[UpscanResponse](successUpscanResponse), incomingLargeMessage)
        val result  = controller().incomingViaUpscan(movementId, messageId)(request)

        status(result) mustBe BAD_REQUEST

        verifyNoInteractions(mockPersistenceConnector)
        verifyNoInteractions(mockPushNotificationsConnector)
        verifyNoInteractions(mockInternalAuthActionProvider)
    }

    "must return BAD_REQUEST when message type is invalid" in forAll(
      arbitrary[UpscanSuccessResponse],
      arbitraryMovementId.arbitrary,
      arbitraryMessageId.arbitrary
    ) {
      (successUpscanResponse, movementId, messageId) =>
        val source: Source[ByteString, _] = singleUseStringSource("abc")

        when(mockUpscanConnector.streamFile(DownloadUrl(eqTo(successUpscanResponse.downloadUrl.value)))(any(), any(), any()))
          .thenReturn(EitherT.rightT(source))

        when(mockMessageTypeExtractor.extractFromBody(any()))
          .thenReturn(EitherT.leftT[Future, MessageType](MessageTypeExtractionError.InvalidMessageType("abcde")))

        val request = fakeRequestLargeMessage(Json.toJson[UpscanResponse](successUpscanResponse), incomingLargeMessage)
        val result  = controller().incomingViaUpscan(movementId, messageId)(request)

        status(result) mustBe BAD_REQUEST

        verifyNoInteractions(mockPersistenceConnector)
        verifyNoInteractions(mockPushNotificationsConnector)
        verifyNoInteractions(mockInternalAuthActionProvider)
        verifyNoInteractions(mockStatusMonitoringService)
    }

    "must return INTERNAL_SERVER_ERROR when persistence service fails unexpectedly" in forAll(
      arbitrary[UpscanSuccessResponse],
      arbitraryMovementId.arbitrary,
      arbitraryMessageId.arbitrary,
      arbitrary[MessageType]
    ) {
      (successUpscanResponse, movementId, messageId, messageType) =>
        val source: Source[ByteString, _] = singleUseStringSource("abc")

        when(mockUpscanConnector.streamFile(DownloadUrl(eqTo(successUpscanResponse.downloadUrl.value)))(any(), any(), any()))
          .thenReturn(EitherT.rightT(source))

        when(mockMessageTypeExtractor.extractFromBody(any())).thenReturn(EitherT.rightT[Future, MessageTypeExtractionError](messageType))

        when(mockPersistenceConnector.postBody(MovementId(eqTo(movementId.value)), MessageId(eqTo(messageId.value)), eqTo(messageType), any())(any(), any()))
          .thenReturn(EitherT.fromEither(Left(Unexpected(None))))

        val request = fakeRequestLargeMessage(Json.toJson[UpscanResponse](successUpscanResponse), incomingLargeMessage)
        val result  = controller().incomingViaUpscan(movementId, messageId)(request)

        status(result) mustBe INTERNAL_SERVER_ERROR

        verifyNoInteractions(mockPushNotificationsConnector)
        verifyNoInteractions(mockInternalAuthActionProvider)

        // It was our fault, not other bits, so we should still report availability
        verify(mockStatusMonitoringService, times(1)).incoming(
          MovementId(eqTo(movementId.value)),
          MessageId(eqTo(messageId.value)),
          eqTo(messageType)
        )(any(), any())
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
        mockPushNotificationsConnector.postSubmissionNotification(
          MovementId(eqTo(movementId.value)),
          MessageId(eqTo(messageId.value)),
          eqTo(ppnsMessage)
        )(any[HeaderCarrier], any[ExecutionContext])
      )
        .thenReturn(EitherT.rightT(()))

      val request = fakeRequestLargeMessage(Json.toJson(sdesResponse), sdesCallback)

      val result = controller().handleSdesResponse()(request)

      status(result) mustBe OK

      verify(mockPushNotificationsConnector, times(1)).postSubmissionNotification(
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

      verifyNoInteractions(mockInternalAuthActionProvider)
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
          .postSubmissionNotification(MovementId(eqTo(movementId.value)), MessageId(eqTo(messageId.value)), eqTo(ppnsMessage))(
            any[HeaderCarrier],
            any[ExecutionContext]
          )
      )
        .thenReturn(EitherT.fromEither(Left(PushNotificationError.Unexpected(None))))
      val request = fakeRequestLargeMessage(Json.toJson(sdesResponse), sdesCallback)

      val result = controller().handleSdesResponse()(request)

      status(result) mustBe OK
      verify(mockPushNotificationsConnector, times(1)).postSubmissionNotification(
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

      verifyNoInteractions(mockInternalAuthActionProvider)
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
        case x                                          => fail(s"Notification type $x was not expected")
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
          .postSubmissionNotification(MovementId(eqTo(movementId.value)), MessageId(eqTo(messageId.value)), eqTo(ppnsMessage))(
            any[HeaderCarrier],
            any[ExecutionContext]
          )
      ).thenReturn(EitherT.rightT(()))

      val request = fakeRequestLargeMessage(Json.toJson(sdesResponse), sdesCallback)

      val result = controller().handleSdesResponse()(request)

      status(result) mustBe INTERNAL_SERVER_ERROR

      verify(mockPushNotificationsConnector, times(1)).postSubmissionNotification(
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

      verifyNoInteractions(mockInternalAuthActionProvider)
    }

    "must return BAD_REQUEST for SDES malformed callback" in {
      val request = fakeRequestLargeMessage(Json.toJson("reference" -> "abc"), sdesCallback)

      val result = controller().handleSdesResponse()(request)

      status(result) mustBe BAD_REQUEST

      verifyNoInteractions(mockInternalAuthActionProvider)
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
