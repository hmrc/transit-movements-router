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

package uk.gov.hmrc.transitmovementsrouter.controllers

import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import cats.data.EitherT
import play.api.libs.json.Json
import play.api.mvc.Action
import play.api.mvc.ControllerComponents
import play.api.mvc.DefaultActionBuilder
import play.api.mvc.Result
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController
import uk.gov.hmrc.play.http.HeaderCarrierConverter
import uk.gov.hmrc.transitmovementsrouter.connectors.PersistenceConnector
import uk.gov.hmrc.transitmovementsrouter.controllers.actions.MessageSizeActionProvider
import uk.gov.hmrc.transitmovementsrouter.controllers.errors.ConvertError
import uk.gov.hmrc.transitmovementsrouter.controllers.stream.StreamingParsers
import uk.gov.hmrc.transitmovementsrouter.models.EoriNumber
import uk.gov.hmrc.transitmovementsrouter.models.MessageId
import uk.gov.hmrc.transitmovementsrouter.models.MessageType
import uk.gov.hmrc.transitmovementsrouter.models.MessageType._
import uk.gov.hmrc.transitmovementsrouter.models.MovementId
import uk.gov.hmrc.transitmovementsrouter.models.MovementType
import uk.gov.hmrc.transitmovementsrouter.services.MessageTypeHeaderExtractor
import uk.gov.hmrc.transitmovementsrouter.services.RoutingService
import uk.gov.hmrc.transitmovementsrouter.services.StreamingMessageTrimmer
import uk.gov.hmrc.transitmovementsrouter.services.error.RoutingError

import javax.inject.Inject
import scala.concurrent.Future

class MessagesController @Inject() (
  cc: ControllerComponents,
  routingService: RoutingService,
  persistenceConnector: PersistenceConnector,
  trimmer: StreamingMessageTrimmer,
  messageSize: MessageSizeActionProvider
)(implicit
  val materializer: Materializer
) extends BackendController(cc)
    with StreamingParsers
    with ConvertError {

  def outgoing(eori: EoriNumber, movementType: MovementType, movementId: MovementId, messageId: MessageId): Action[Source[ByteString, _]] = Action.async(
    streamFromFile
  ) {
    implicit request =>
      implicit val hc: HeaderCarrier = HeaderCarrierConverter.fromRequest(request)
      (for {
        messageType <- MessageTypeHeaderExtractor.extract(request.headers).asPresentation
        submitted   <- submit(movementType, movementId, messageId, messageType, request.body)
      } yield submitted).fold[Result](
        error => Status(error.code.statusCode)(Json.toJson(error)),
        _ => Accepted
      )
  }

  def incoming(ids: (MovementId, MessageId)): Action[Source[ByteString, _]] =
    (DefaultActionBuilder.apply(cc.parsers.anyContent) andThen messageSize()).async(streamFromFile) {
      implicit request =>
        implicit val hc: HeaderCarrier = HeaderCarrierConverter.fromRequest(request)
        val (movementId, messageId)    = ids

        (for {
          messageType <- MessageTypeHeaderExtractor.extract(request.headers).asPresentation
          newSource = trimmer.trim(request.body)
          persistenceResponse <- persistenceConnector.post(movementId, messageId, messageType, newSource).asPresentation
        } yield persistenceResponse)
          .fold[Result](
            error => Status(error.code.statusCode)(Json.toJson(error)),
            response => Created.withHeaders("X-Message-Id" -> response.messageId.value)
          )
    }

  private def submit(
    movementType: MovementType,
    movementId: MovementId,
    messageId: MessageId,
    messageType: MessageType,
    payload: Source[ByteString, _]
  )(implicit hc: HeaderCarrier) =
    if (isRequestMessageType(messageType)) {
      routingService.submitMessage(movementType, movementId, messageId, messageType, payload).asPresentation
    } else {
      val message                                              = s"An unexpected error occurred - got a ${messageType.code}"
      val unexpectedError: EitherT[Future, RoutingError, Unit] = EitherT.left(Future.successful(RoutingError.Unexpected(message, None)))
      unexpectedError.asPresentation
    }

}
