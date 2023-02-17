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
import play.api.libs.Files.TemporaryFileCreator
import play.api.libs.json.Json
import play.api.mvc.Action
import play.api.mvc.ControllerComponents
import play.api.mvc.DefaultActionBuilder
import play.api.mvc.Result
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController
import uk.gov.hmrc.play.http.HeaderCarrierConverter
import uk.gov.hmrc.transitmovementsrouter.connectors.PersistenceConnector
import uk.gov.hmrc.transitmovementsrouter.connectors.PushNotificationsConnector
import uk.gov.hmrc.transitmovementsrouter.controllers.actions.AuthenticateEISToken
import uk.gov.hmrc.transitmovementsrouter.controllers.errors.ConvertError
import uk.gov.hmrc.transitmovementsrouter.controllers.errors.PresentationError
import uk.gov.hmrc.transitmovementsrouter.controllers.stream.StreamingParsers
import uk.gov.hmrc.transitmovementsrouter.models.ConversationId
import uk.gov.hmrc.transitmovementsrouter.models.EoriNumber
import uk.gov.hmrc.transitmovementsrouter.models.MessageId
import uk.gov.hmrc.transitmovementsrouter.models.MessageType
import uk.gov.hmrc.transitmovementsrouter.models.MovementId
import uk.gov.hmrc.transitmovementsrouter.models.MovementType
import uk.gov.hmrc.transitmovementsrouter.models.RequestMessageType
import uk.gov.hmrc.transitmovementsrouter.services.EISMessageTransformers
import uk.gov.hmrc.transitmovementsrouter.services.RoutingService
import uk.gov.hmrc.transitmovementsrouter.services.UpscanService
import uk.gov.hmrc.transitmovementsrouter.services.MessageTypeExtractor

import javax.inject.Inject
import scala.concurrent.Future

class MessagesController @Inject() (
  cc: ControllerComponents,
  routingService: RoutingService,
  persistenceConnector: PersistenceConnector,
  pushNotificationsConnector: PushNotificationsConnector,
  upscanService: UpscanService,
  messageTypeExtractor: MessageTypeExtractor,
  authenticateEISToken: AuthenticateEISToken,
  eisMessageTransformers: EISMessageTransformers
)(implicit
  val materializer: Materializer,
  val temporaryFileCreator: TemporaryFileCreator
) extends BackendController(cc)
    with StreamingParsers
    with ConvertError {

  def outgoing(eori: EoriNumber, movementType: MovementType, movementId: MovementId, messageId: MessageId): Action[Source[ByteString, _]] =
    DefaultActionBuilder.apply(cc.parsers.anyContent).stream {
      implicit request =>
        implicit val hc: HeaderCarrier = HeaderCarrierConverter.fromRequest(request)
        (for {
          messageType        <- messageTypeExtractor.extractFromHeaders(request.headers).asPresentation
          requestMessageType <- filterRequestMessageType(messageType)
          submitted          <- routingService.submitMessage(movementType, movementId, messageId, requestMessageType, request.body).asPresentation
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
          persistenceResponse <- persistenceConnector.post(movementId, messageId, messageType, request.body).asPresentation
          _ = pushNotificationsConnector.post(movementId, persistenceResponse.messageId, request.body).asPresentation
        } yield persistenceResponse)
          .fold[Result](
            error => Status(error.code.statusCode)(Json.toJson(error)),
            response => Created.withHeaders("X-Message-Id" -> response.messageId.value)
          )
    }

  def incomingLargeMessage(movementId: MovementId, messageId: MessageId) = Action.async(cc.parsers.json) {
    implicit request =>
      upscanService.parseUpscanResponse(request.body)

      Future.successful(Ok)
  }

  private def filterRequestMessageType(messageType: MessageType): EitherT[Future, PresentationError, RequestMessageType] = messageType match {
    case t: RequestMessageType => EitherT.rightT(t)
    case _                     => EitherT.leftT(PresentationError.badRequestError(s"${messageType.code} is not valid for requests"))
  }

}
