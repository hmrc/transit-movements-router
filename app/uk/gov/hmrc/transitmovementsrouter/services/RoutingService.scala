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

import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import cats.data.EitherT
import com.google.inject._
import play.api.Logging
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.transitmovementsrouter.connectors.EISConnector
import uk.gov.hmrc.transitmovementsrouter.connectors.EISConnectorProvider
import uk.gov.hmrc.transitmovementsrouter.models._
import uk.gov.hmrc.transitmovementsrouter.models.errors.RoutingError

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

@ImplementedBy(classOf[RoutingServiceImpl])
trait RoutingService {

  def submitMessage(
    movementType: MovementType,
    movementId: MovementId,
    messageId: MessageId,
    payload: Source[ByteString, ?],
    customsOffice: CustomsOffice,
    versionHeader: APIVersionHeader,
    optionalHeader: Option[String]
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
    payload: Source[ByteString, ?],
    customsOffice: CustomsOffice,
    versionHeader: APIVersionHeader,
    optionalHeader: Option[String]
  )(implicit hc: HeaderCarrier, ec: ExecutionContext): EitherT[Future, RoutingError, Unit] =
    EitherT(
      eisConnectorSelector(customsOffice, versionHeader, optionalHeader)
        .post(movementId, messageId, payload.via(eisMessageTransformers.wrap), hc)
    )

  private def eisConnectorSelector(customsOffice: CustomsOffice, versionHeader: APIVersionHeader, optionalHeader: Option[String]): EISConnector =
    (customsOffice.isGB, versionHeader, optionalHeader) match {
      case (true, APIVersionHeader.v3_0, Some(_))  => logger.info("routing to optional header GB SITLS route"); messageConnectorProvider.gbV3_0c
      case (false, APIVersionHeader.v3_0, Some(_)) => logger.info("routing to optional header XI SITLS route"); messageConnectorProvider.xiV3_0c
      case (true, APIVersionHeader.v3_0, None)     => logger.info("routing to GB"); messageConnectorProvider.gbV3_0
      case (false, APIVersionHeader.v3_0, None)    => logger.info("routing to XI"); messageConnectorProvider.xiV3_0
    }
}
