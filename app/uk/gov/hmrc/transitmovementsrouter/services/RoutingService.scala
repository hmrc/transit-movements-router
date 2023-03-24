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

import akka.stream.scaladsl.Source
import akka.util.ByteString
import cats.data.EitherT
import com.google.inject._
import play.api.Logging
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.transitmovementsrouter.connectors.EISConnector
import uk.gov.hmrc.transitmovementsrouter.connectors.EISConnectorProvider
import uk.gov.hmrc.transitmovementsrouter.models._
import uk.gov.hmrc.transitmovementsrouter.services.error.RoutingError

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

@ImplementedBy(classOf[RoutingServiceImpl])
trait RoutingService {

  def submitMessage(
    movementType: MovementType,
    movementId: MovementId,
    messageId: MessageId,
    payload: Source[ByteString, _],
    customsOffice: CustomsOffice
  )(implicit hc: HeaderCarrier, ec: ExecutionContext): EitherT[Future, RoutingError, Unit]

}

@Singleton
class RoutingServiceImpl @Inject() (
  eisMessageTransformers: EISMessageTransformers,
  messageConnectorProvider: EISConnectorProvider
) extends RoutingService
    with Logging {

  override def submitMessage(
    movementType: MovementType,
    movementId: MovementId,
    messageId: MessageId,
    payload: Source[ByteString, _],
    customsOffice: CustomsOffice
  )(implicit hc: HeaderCarrier, ec: ExecutionContext): EitherT[Future, RoutingError, Unit] =
    for {
      connector <- selectConnector(customsOffice)
      _         <- EitherT(connector.post(movementId, messageId, payload.via(eisMessageTransformers.wrap), hc))
    } yield ()

  private def selectConnector(customsOffice: CustomsOffice): EitherT[Future, RoutingError, EISConnector] = EitherT {
    if (customsOffice.isGB) Future.successful(Right(messageConnectorProvider.gb))
    else Future.successful(Right(messageConnectorProvider.xi))
  }
}
