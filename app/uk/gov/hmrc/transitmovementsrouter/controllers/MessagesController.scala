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

import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import cats.data.EitherT
import play.api.Logging
import play.api.libs.Files.TemporaryFileCreator
import play.api.libs.json.Json
import play.api.mvc.Action
import play.api.mvc.ControllerComponents
import play.api.mvc.DefaultActionBuilder
import play.api.mvc.Result
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.HeaderNames
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController
import uk.gov.hmrc.play.http.HeaderCarrierConverter
import uk.gov.hmrc.transitmovementsrouter.config.AppConfig
import uk.gov.hmrc.transitmovementsrouter.connectors.PersistenceConnector
import uk.gov.hmrc.transitmovementsrouter.connectors.PushNotificationsConnector
import uk.gov.hmrc.transitmovementsrouter.controllers.actions.AuthenticateEISToken
import uk.gov.hmrc.transitmovementsrouter.controllers.errors.ConvertError
import uk.gov.hmrc.transitmovementsrouter.controllers.errors.PresentationError
import uk.gov.hmrc.transitmovementsrouter.controllers.stream.StreamingParsers
import uk.gov.hmrc.transitmovementsrouter.models._
import uk.gov.hmrc.transitmovementsrouter.models.requests.MessageUpdate
import uk.gov.hmrc.transitmovementsrouter.models.sdes.SdesNotification
import uk.gov.hmrc.transitmovementsrouter.models.sdes.SdesNotificationType

import java.util.UUID
import uk.gov.hmrc.transitmovementsrouter.models.responses.UpscanResponse
import uk.gov.hmrc.transitmovementsrouter.models.responses.UpscanResponse.DownloadUrl
import uk.gov.hmrc.transitmovementsrouter.services.CustomOfficeExtractorService
import uk.gov.hmrc.transitmovementsrouter.services.EISMessageTransformers
import uk.gov.hmrc.transitmovementsrouter.services.MessageTypeExtractor
import uk.gov.hmrc.transitmovementsrouter.services.ObjectStoreService
import uk.gov.hmrc.transitmovementsrouter.services.RoutingService
import uk.gov.hmrc.transitmovementsrouter.services.SDESService
import uk.gov.hmrc.transitmovementsrouter.utils.RouterHeaderNames

import javax.inject.Inject
import scala.concurrent.Future

class MessagesController @Inject() (
  cc: ControllerComponents,
  routingService: RoutingService,
  persistenceConnector: PersistenceConnector,
  pushNotificationsConnector: PushNotificationsConnector,
  messageTypeExtractor: MessageTypeExtractor,
  authenticateEISToken: AuthenticateEISToken,
  eisMessageTransformers: EISMessageTransformers,
  objectStoreService: ObjectStoreService,
  customOfficeExtractorService: CustomOfficeExtractorService,
  sdesService: SDESService,
  val config: AppConfig
)(implicit
  val materializer: Materializer,
  val temporaryFileCreator: TemporaryFileCreator
) extends BackendController(cc)
    with StreamingParsers
    with ConvertError
    with UpscanResponseParser
    with ObjectStoreURIExtractor
    with ContentTypeRouting
    with SdesResponseParser
    with Logging {

  def outgoing(eori: EoriNumber, movementType: MovementType, movementId: MovementId, messageId: MessageId) =
    contentTypeRoute {
      case Some(_) => outgoingSmallMessage(eori, movementType, movementId, messageId)
      case None    => outgoingLargeMessage(eori, movementType, movementId, messageId)
    }

  private def outgoingLargeMessage(eori: EoriNumber, movementType: MovementType, movementId: MovementId, messageId: MessageId) =
    Action.async {
      implicit request =>
        implicit val hc: HeaderCarrier = HeaderCarrierConverter.fromRequest(request)
        (for {
          messageType                 <- messageTypeExtractor.extractFromHeaders(request.headers).asPresentation
          requestMessageType          <- filterRequestMessageType(messageType)
          objectStoreResourceLocation <- extractObjectStoreURIHeader(request.headers)
          source                      <- objectStoreService.getObjectStoreFile(objectStoreResourceLocation).asPresentation
          customOffice                <- customOfficeExtractorService.extractCustomOffice(source, requestMessageType).asPresentation
          objectSummary <- objectStoreService
            .storeOutgoing(objectStoreResourceLocation)
            .asPresentation
          result <- sdesService.send(movementId, messageId, objectSummary).asPresentation
        } yield result).fold[Result](
          error => Status(error.code.statusCode)(Json.toJson(error)),
          _ => Accepted
        )
    }

  private def outgoingSmallMessage(eori: EoriNumber, movementType: MovementType, movementId: MovementId, messageId: MessageId): Action[Source[ByteString, _]] =
    DefaultActionBuilder.apply(cc.parsers.anyContent).stream {
      implicit request =>
        implicit val hc: HeaderCarrier = HeaderCarrierConverter.fromRequest(request)
        (for {
          messageType        <- messageTypeExtractor.extractFromHeaders(request.headers).asPresentation
          requestMessageType <- filterRequestMessageType(messageType)
          customOffice       <- customOfficeExtractorService.extractCustomOffice(request.body, requestMessageType).asPresentation
          submitted          <- routingService.submitMessage(movementType, movementId, messageId, request.body, customOffice).asPresentation
        } yield submitted).fold[Result](
          error => Status(error.code.statusCode)(Json.toJson(error)),
          _ => Accepted
        )
    }

  def incoming(ids: ConversationId): Action[Source[ByteString, _]] =
    authenticateEISToken.stream(transformer = eisMessageTransformers.unwrap) {
      implicit request =>
        implicit val hc: HeaderCarrier = HeaderCarrierConverter.fromRequest(request)
        val (movementId, messageId)    = ids.toMovementAndMessageId

        (for {
          messageType         <- messageTypeExtractor.extract(request.headers, request.body).asPresentation
          persistenceResponse <- persistenceConnector.postBody(movementId, messageId, messageType, request.body).asPresentation
          _ = pushNotificationsConnector.post(movementId, persistenceResponse.messageId, Some(request.body)).asPresentation
        } yield persistenceResponse)
          .fold[Result](
            {
              error =>
                if (config.logIncoming) {
                  logger.error(s"""Unable to process message from EIS -- bad request:
                       |
                       |Request ID: ${request.headers.get(HeaderNames.xRequestId).getOrElse("unavailable")}
                       |Correlation ID: ${request.headers.get(RouterHeaderNames.CORRELATION_ID).getOrElse("unavailable")}
                       |Conversation ID: ${request.headers.get(RouterHeaderNames.CONVERSATION_ID).getOrElse("unavailable")}
                       |
                       |error is ${Json.toJson(error)}""".stripMargin)
                }
                Status(error.code.statusCode)(Json.toJson(error))
            },
            response => Created.withHeaders("X-Message-Id" -> response.messageId.value)
          )
    }

  def incomingLargeMessage(movementId: MovementId, messageId: MessageId) = Action.async(cc.parsers.json) {
    implicit request =>
      implicit val hc: HeaderCarrier = HeaderCarrierConverter.fromRequest(request)
      (for {
        upscanResponse <- parseAndLogUpscanResponse(request.body)
        downloadUrl    <- handleUpscanSuccessResponse(upscanResponse)
        objectSummary <- objectStoreService
          .storeIncoming(downloadUrl, movementId, messageId)
          .asPresentation
        objectStoreResourceLocation <- extractObjectStoreResourceLocation(ObjectStoreURI(objectSummary.location.asUri))
        source                      <- objectStoreService.getObjectStoreFile(objectStoreResourceLocation).asPresentation
        messageType                 <- messageTypeExtractor.extractFromBody(source).asPresentation
        persistenceResponse <- persistenceConnector
          .postObjectStoreUri(movementId, messageId, messageType, ObjectStoreURI(objectSummary.location.asUri))
          .asPresentation
        _ = pushNotificationsConnector.post(movementId, messageId, None).asPresentation
      } yield persistenceResponse)
        .fold[Result](
          presentationError => Status(presentationError.code.statusCode)(Json.toJson(presentationError)),
          response => Created.withHeaders("X-Message-Id" -> response.messageId.value)
        )
  }

  private def handleUpscanSuccessResponse(upscanResponse: UpscanResponse): EitherT[Future, PresentationError, DownloadUrl] =
    EitherT {
      Future.successful(upscanResponse.downloadUrl.toRight {
        PresentationError.badRequestError("Upscan failed to process file")
      })
    }

  private def filterRequestMessageType(messageType: MessageType): EitherT[Future, PresentationError, RequestMessageType] = messageType match {
    case t: RequestMessageType => EitherT.rightT(t)
    case _                     => EitherT.leftT(PresentationError.badRequestError(s"${messageType.code} is not valid for requests"))
  }

  private def extractMovementMessageId(sdesResponse: SdesNotification): (MovementId, MessageId) =
    ConversationId(
      UUID.fromString(
        sdesResponse.conversationId.get.value
      )
    ).toMovementAndMessageId

  private def updateStatus(movementId: MovementId, messageId: MessageId, messageStatus: MessageStatus)(implicit
    hc: HeaderCarrier
  ): EitherT[Future, PresentationError, Unit] =
    persistenceConnector
      .patchMessageStatus(movementId, messageId, MessageUpdate(messageStatus))
      .asPresentation

  def handleSdesResponse() =
    Action.async(parse.json) {
      implicit request =>
        implicit val hc: HeaderCarrier = HeaderCarrierConverter.fromRequest(request)
        (for {
          sdesResponse <- parseAndLogSdesResponse(request.body)
          (movementId, messageId) = extractMovementMessageId(sdesResponse)
          persistenceResponse <- sdesResponse.notification match {
            case SdesNotificationType.FileProcessed =>
              updateStatus(movementId, messageId, MessageStatus.Success)
            case SdesNotificationType.FileProcessingFailure =>
              updateStatus(movementId, messageId, MessageStatus.Failed)
            case _ => EitherT.rightT[Future, PresentationError]((): Unit)
          }
        } yield persistenceResponse).fold[Result](
          error => Status(error.code.statusCode)(Json.toJson(error)),
          _ => Ok
        )
    }
}
