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

import cats.data.EitherT
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Sink
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import play.api.Logging
import play.api.http.MimeTypes
import play.api.libs.Files.TemporaryFileCreator
import play.api.libs.json.JsValue
import play.api.libs.json.Json
import play.api.mvc.Action
import play.api.mvc.ControllerComponents
import play.api.mvc.Request
import play.api.mvc.Result
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.HeaderNames
import uk.gov.hmrc.internalauth.client._
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController
import uk.gov.hmrc.play.http.HeaderCarrierConverter
import uk.gov.hmrc.transitmovementsrouter.config.AppConfig
import uk.gov.hmrc.transitmovementsrouter.connectors.PersistenceConnector
import uk.gov.hmrc.transitmovementsrouter.connectors.PushNotificationsConnector
import uk.gov.hmrc.transitmovementsrouter.connectors.UpscanConnector
import uk.gov.hmrc.transitmovementsrouter.controllers.actions.AuthenticateEISToken
import uk.gov.hmrc.transitmovementsrouter.controllers.actions.InternalAuthActionProvider
import uk.gov.hmrc.transitmovementsrouter.controllers.errors.ConvertError
import uk.gov.hmrc.transitmovementsrouter.controllers.errors.PresentationError
import uk.gov.hmrc.transitmovementsrouter.controllers.stream.StreamingParsers
import uk.gov.hmrc.transitmovementsrouter.models.AuditType.NCTSRequestedMissingMovement
import uk.gov.hmrc.transitmovementsrouter.models.AuditType.NCTSToTraderSubmissionSuccessful
import uk.gov.hmrc.transitmovementsrouter.models._
import uk.gov.hmrc.transitmovementsrouter.models.requests.MessageUpdate
import uk.gov.hmrc.transitmovementsrouter.models.responses.UpscanResponse.DownloadUrl
import uk.gov.hmrc.transitmovementsrouter.models.responses.UpscanFailedResponse
import uk.gov.hmrc.transitmovementsrouter.models.responses.UpscanResponse
import uk.gov.hmrc.transitmovementsrouter.models.responses.UpscanSuccessResponse
import uk.gov.hmrc.transitmovementsrouter.models.sdes.SdesNotification
import uk.gov.hmrc.transitmovementsrouter.models.sdes.SdesNotificationType
import uk.gov.hmrc.transitmovementsrouter.services._
import uk.gov.hmrc.transitmovementsrouter.utils.RouterHeaderNames
import uk.gov.hmrc.transitmovementsrouter.utils.StreamWithFile

import java.util.UUID
import javax.inject.Inject
import scala.annotation.unused
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

object MessagesController extends ConvertError {

  implicit class PresentationEitherTHelper[E: Converter, A](val value: EitherT[Future, E, A]) {

    def asPresentationWithMessageType(messageTypeMaybe: Option[MessageType])(implicit
      ec: ExecutionContext
    ): EitherT[Future, (PresentationError, Option[MessageType]), A] =
      value.asPresentation.leftMap(
        x => (x, messageTypeMaybe)
      )
  }
}

class MessagesController @Inject() (
  cc: ControllerComponents,
  routingService: RoutingService,
  persistenceConnector: PersistenceConnector,
  pushNotificationsConnector: PushNotificationsConnector,
  messageTypeExtractor: MessageTypeExtractor,
  authenticateEISToken: AuthenticateEISToken,
  eisMessageTransformers: EISMessageTransformers,
  objectStoreService: ObjectStoreService,
  upscanConnector: UpscanConnector,
  customOfficeExtractorService: CustomOfficeExtractorService,
  sdesService: SDESService,
  internalAuth: InternalAuthActionProvider,
  statusMonitoringService: ServiceMonitoringService,
  auditService: AuditingService,
  val config: AppConfig
)(implicit
  val materializer: Materializer,
  val temporaryFileCreator: TemporaryFileCreator
) extends BackendController(cc)
    with StreamingParsers
    with ConvertError
    with UpscanResponseParser
    with ContentTypeRouting
    with SdesResponseParser
    with Logging
    with StreamWithFile {

  private val predicate: Predicate.Permission =
    Predicate.Permission(Resource(ResourceType("transit-movements-router"), ResourceLocation("message")), IAAction("WRITE"))

  def outgoing(@unused eori: EoriNumber, movementType: MovementType, movementId: MovementId, messageId: MessageId): Action[Source[ByteString, ?]] = {
    def viaEIS(messageType: MessageType, customsOffice: CustomsOffice, source: Source[ByteString, ?])(implicit
      hc: HeaderCarrier
    ): EitherT[Future, PresentationError, Status] =
      for {
        _ <- routingService.submitMessage(movementType, movementId, messageId, source, customsOffice).asPresentation
        _ = statusMonitoringService.outgoing(movementId, messageId, messageType, customsOffice)
      } yield Created

    def viaSDES(source: Source[ByteString, ?])(implicit hc: HeaderCarrier): EitherT[Future, PresentationError, Status] =
      for {
        objectStoreFile <- objectStoreService.storeOutgoing(ConversationId(movementId, messageId), source).asPresentation
        _               <- sdesService.send(movementId, messageId, objectStoreFile).asPresentation
      } yield Accepted

    internalAuth(predicate).async(streamFromMemory) {

      implicit request =>
        (for {
          source             <- reUsableOutgoingSource(request)
          messageType        <- messageTypeExtractor.extractFromHeaders(request.headers).asPresentation
          requestMessageType <- filterRequestMessageType(messageType)
          customsOffice      <- customOfficeExtractorService.extractCustomOffice(source.headOption.get, requestMessageType).asPresentation
          size               <- calculateSize(source.lift(1).get)
          submitted <-
            if (config.eisSizeLimit >= size) viaEIS(messageType, customsOffice, source.lift(2).get)
            else viaSDES(source.lift(3).get)
        } yield submitted)
          .valueOr(
            error => Status(error.code.statusCode)(Json.toJson(error))
          )
    }
  }

  private def materializeOutgoingSource(source: Source[ByteString, ?]): EitherT[Future, PresentationError, Seq[ByteString]] =
    EitherT(
      source
        .runWith(Sink.seq)
        .map(Right(_): Either[PresentationError, Seq[ByteString]])
        .recover {
          error =>
            Left(PresentationError.internalServiceError(cause = Some(error)))
        }
    )

  // Function to materialize the source into a Seq[ByteString] with error handling
  private def materializeIncomingSource(source: Source[ByteString, ?]): EitherT[Future, PresentationError, Seq[ByteString]] =
    EitherT(
      source
        .via(eisMessageTransformers.unwrap)
        .runWith(Sink.seq)
        .map(Right(_): Either[PresentationError, Seq[ByteString]])
        .recover {
          case error: Throwable if error.toString.contains("IllegalStateException") || error.toString.contains("WFCException") =>
            if (config.logIncoming) {
              logger.error(
                s"""Unable to process message from EIS -- bad request:
               |
               |Message: ${error.getMessage}
               |
               |Failed to transform XML""".stripMargin,
                error
              )
            }
            Left(PresentationError.badRequestError(error.getMessage))
          case error: Throwable =>
            if (config.logIncoming) {
              logger.error(
                s"""Unable to process message from EIS -- internal server error:
               |
               |Message: ${error.getMessage}
               |
               |Failed to transform """.stripMargin,
                error
              )
            }
            Left(PresentationError.internalServiceError(cause = Some(error)))
        }
    )

  // Function to create a new source from the materialized sequence
  private def createReusableSource(seq: Seq[ByteString]): Source[ByteString, ?] = Source(seq.toList)

  // Materialize the source and create multiple new sources with error handling
  private def reUsableIncomingSource(request: Request[Source[ByteString, ?]]): EitherT[Future, PresentationError, List[Source[ByteString, ?]]] = for {
    byteStringSeq <- materializeIncomingSource(request.body)
  } yield List.fill(4)(createReusableSource(byteStringSeq))

  private def reUsableOutgoingSource(request: Request[Source[ByteString, ?]]): EitherT[Future, PresentationError, List[Source[ByteString, ?]]] = for {
    byteStringSeq <- materializeOutgoingSource(request.body)
  } yield List.fill(4)(createReusableSource(byteStringSeq))

  // Function to calculate the size using EitherT
  private def calculateSize(source: Source[ByteString, ?]): EitherT[Future, PresentationError, Long] = {
    val sizeFuture: Future[Either[PresentationError, Long]] = source
      .map(_.size.toLong)
      .runWith(Sink.fold(0L)(_ + _))
      .map(
        size => Right(size): Either[PresentationError, Long]
      )
      .recover {
        case _: Exception => Left(PresentationError.internalServiceError())
      }

    EitherT(sizeFuture)
  }

  private def extractAuditMessageType(messageType: MessageType): EitherT[Future, PresentationError, AuditType] =
    EitherT.fromOption[Future](messageType.auditType, PresentationError.badRequestError(s"$messageType is not a ResponseMessageType"))

  def incomingViaEIS(ids: ConversationId): Action[Source[ByteString, ?]] =
    authenticateEISToken.async(streamFromMemory) {

      implicit request =>
        import MessagesController.PresentationEitherTHelper

        val (movementId, triggerId) = ids.toMovementAndMessageId
        (for {
          source <- reUsableIncomingSource(request).leftMap(
            err => (err, None)
          )

          messageType <- messageTypeExtractor.extract(request.headers, source.headOption.get).asPresentationWithMessageType(None)

          auditMsg <- extractAuditMessageType(messageType).leftMap(
            err => (err, Option(messageType))
          )
          _ = statusMonitoringService.incoming(movementId, triggerId, messageType)

          size <- calculateSize(source.lift(1).get).leftMap(
            err => (err, Option(messageType))
          )
          persistenceResponse <- persistStream(movementId, triggerId, messageType, source.lift(2).get).leftMap {
            err =>
              if (err.code.statusCode == NOT_FOUND)
                auditService.auditStatusEvent(
                  NCTSRequestedMissingMovement,
                  Some(Json.toJson(err)),
                  Some(movementId),
                  None,
                  None,
                  Some(messageType.movementType),
                  Some(messageType),
                  None
                )
              (err, Option(messageType))
          }
          _ = auditService.auditMessageEvent(
            auditType = auditMsg,
            contentType = MimeTypes.XML,
            contentLength = size,
            payload = source.lift(3).get,
            movementId = Some(movementId),
            messageId = Some(persistenceResponse.messageId),
            enrolmentEORI = Some(persistenceResponse.eori),
            movementType = Some(messageType.movementType),
            messageType = Some(messageType),
            clientId = persistenceResponse.clientId,
            isTransitional = persistenceResponse.isTransitional
          )
          _ = auditService.auditStatusEvent(
            NCTSToTraderSubmissionSuccessful,
            None,
            Some(movementId),
            Some(persistenceResponse.messageId),
            enrolmentEORI = Some(persistenceResponse.eori),
            Some(messageType.movementType),
            Some(messageType),
            clientId = persistenceResponse.clientId
          )
          _ = logIncomingSuccess(movementId, triggerId, persistenceResponse.messageId, messageType)
        } yield persistenceResponse)
          .fold[Result](
            {
              error =>
                if (config.logIncoming) {
                  logger.error(s"""Unable to process message from EIS :
                         |
                         |${generateRequestLog(movementId, triggerId, error._2)}

                         |error is ${Json.toJson(error._1)}""".stripMargin)
                }
                Status(error._1.code.statusCode)(Json.toJson(error._1))
            },
            response => Created.withHeaders("X-Message-Id" -> response.messageId.value)
          )

    }

  def incomingViaUpscan(movementId: MovementId, triggerId: MessageId): Action[JsValue] = Action.async(cc.parsers.json) {
    implicit request =>
      implicit val hc: HeaderCarrier = HeaderCarrierConverter.fromRequest(request)
      (for {
        upscanResponse      <- parseAndLogUpscanResponse(request.body)
        downloadUrl         <- handleUpscanSuccessResponse(upscanResponse)
        source              <- upscanConnector.streamFile(downloadUrl).map(_.via(eisMessageTransformers.unwrap)).asPresentation
        persistenceResponse <- withUpscanSource(movementId, triggerId, source)
      } yield persistenceResponse)
        .fold[Result](
          presentationError =>
            // TODO: Inform SDES of failure
            Status(presentationError.code.statusCode)(Json.toJson(presentationError)),
          response =>
            // TODO: Inform SDES of success
            Created.withHeaders("X-Message-Id" -> response.messageId.value)
        )
  }

  private def withUpscanSource(movementId: MovementId, triggerId: MessageId, source: Source[ByteString, ?])(implicit
    hc: HeaderCarrier
  ): EitherT[Future, PresentationError, PersistenceResponse] =
    withReusableSource(source) {
      fileSource =>
        for {
          messageType <- messageTypeExtractor.extractFromBody(fileSource).asPresentation
          _ = statusMonitoringService.incoming(movementId, triggerId, messageType)
          persistenceResponse <- persistStream(movementId, triggerId, messageType, fileSource)
        } yield persistenceResponse
    }

  private def persistStream(movementId: MovementId, triggerId: MessageId, messageType: MessageType, source: Source[ByteString, ?])(implicit
    hc: HeaderCarrier
  ): EitherT[Future, PresentationError, PersistenceResponse] =
    for {
      persistenceResponse <- persistenceConnector
        .postBody(movementId, triggerId, messageType, source)
        .asPresentation
      // We suppress this as we do want to fire and forget, but if we don't make it part of the flatmap,
      // our system might delete the file before we use it in the stream.
      //
      // suppress drops any errors (as far as this flatmap chain is concerned) and then returns unit, always.
      _ <- pushNotificationsConnector.postMessageReceived(movementId, persistenceResponse.messageId, messageType, source).suppress
    } yield persistenceResponse

  private def handleUpscanSuccessResponse(upscanResponse: UpscanResponse): EitherT[Future, PresentationError, DownloadUrl] =
    EitherT {
      Future.successful {
        upscanResponse match {
          case x: UpscanSuccessResponse => Right(x.downloadUrl)
          case _: UpscanFailedResponse  => Left(PresentationError.badRequestError("Upscan failed to process file"))
        }
      }
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

  private def updateAndSendNotification(movementId: MovementId, messageId: MessageId, messageStatus: MessageStatus, jsonValue: JsValue)(implicit
    hc: HeaderCarrier
  ): EitherT[Future, PresentationError, Unit] =
    for {
      persistenceResponse <- persistenceConnector
        .patchMessageStatus(movementId, messageId, MessageUpdate(messageStatus))
        .asPresentation
      _ = pushNotificationsConnector
        .postSubmissionNotification(
          movementId,
          messageId,
          jsonValue
        )
    } yield persistenceResponse

  def handleSdesResponse(): Action[JsValue] =
    Action.async(parse.json) {
      implicit request =>
        implicit val hc: HeaderCarrier = HeaderCarrierConverter.fromRequest(request)
        // This is used to handle sdes response. Like if we receive bad json response from sdes then we need to report to sdes and log the response.
        parseAndLogSdesResponse(request.body) match {
          case Left(presentationError) =>
            Future.successful(Status(presentationError.code.statusCode)(Json.toJson(presentationError)))
          case Right(sdesResponse) =>
            val (movementId, messageId) = extractMovementMessageId(sdesResponse)
            (for {
              persistenceResponse <- sdesResponse.notification match {
                case SdesNotificationType.FileProcessed =>
                  updateAndSendNotification(
                    movementId,
                    messageId,
                    MessageStatus.Success,
                    Json.toJson(
                      Json.obj(
                        "code" -> "SUCCESS",
                        "message" ->
                          s"The message ${messageId.value} for movement ${movementId.value} was successfully processed"
                      )
                    )
                  )
                case SdesNotificationType.FileProcessingFailure =>
                  updateAndSendNotification(
                    movementId,
                    messageId,
                    MessageStatus.Failed,
                    Json.toJson(
                      PresentationError.internalServiceError()
                    )
                  )
                case _ => EitherT.rightT[Future, PresentationError]((): Unit)
              }
            } yield persistenceResponse).fold[Result](
              presentationError => {
                pushNotificationsConnector.postSubmissionNotification(movementId, messageId, Json.toJson(PresentationError.internalServiceError()))
                Status(presentationError.code.statusCode)(Json.toJson(presentationError))
              },
              _ => Ok
            )
        }
    }

  // Logging methods

  private def generateRequestLog(movementId: MovementId, triggerId: MessageId, messageType: Option[MessageType])(implicit request: Request[?]): String =
    s"""Message Type: ${messageType.map(_.code).getOrElse("unknown")}
       |Movement ID: ${movementId.value}
       |Trigger ID: ${triggerId.value}
       |Request ID: ${request.headers.get(HeaderNames.xRequestId).getOrElse("unavailable")}
       |Correlation ID: ${request.headers.get(RouterHeaderNames.CORRELATION_ID).getOrElse("unavailable")}
       |Conversation ID: ${request.headers.get(RouterHeaderNames.CONVERSATION_ID).getOrElse("unavailable")}""".stripMargin

  private def logIncomingSuccess(movementId: MovementId, triggerId: MessageId, newMessageId: MessageId, messageType: MessageType)(implicit
    request: Request[?]
  ): Unit =
    if (config.logIncoming) {
      logger.info(s"""Received message from EIS
           |
           |${generateRequestLog(movementId, triggerId, Some(messageType))}
           |New Message ID: ${newMessageId.value}""".stripMargin)
    }
}
