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
import com.google.inject.ImplementedBy
import com.google.inject.Inject
import play.api.Logging
import play.api.libs.json.JsValue
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.transitmovementsrouter.connectors.AuditingConnector
import uk.gov.hmrc.transitmovementsrouter.models.APIVersionHeader
import uk.gov.hmrc.transitmovementsrouter.models.AuditType
import uk.gov.hmrc.transitmovementsrouter.models.ClientId
import uk.gov.hmrc.transitmovementsrouter.models.EoriNumber
import uk.gov.hmrc.transitmovementsrouter.models.MessageId
import uk.gov.hmrc.transitmovementsrouter.models.MessageType
import uk.gov.hmrc.transitmovementsrouter.models.MovementId
import uk.gov.hmrc.transitmovementsrouter.models.MovementType

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.control.NonFatal

@ImplementedBy(classOf[AuditingServiceImpl])
trait AuditingService {

  def auditStatusEvent(
    auditType: AuditType,
    payload: Option[JsValue],
    movementId: Option[MovementId],
    messageId: Option[MessageId],
    enrolmentEORI: Option[EoriNumber],
    movementType: Option[MovementType],
    messageType: Option[MessageType],
    clientId: Option[ClientId],
    versionHeader: APIVersionHeader
  )(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): Future[Unit]

  def auditMessageEvent(
    auditType: AuditType,
    contentType: String,
    contentLength: Long,
    payload: Source[ByteString, ?],
    movementId: Option[MovementId],
    messageId: Option[MessageId],
    enrolmentEORI: Option[EoriNumber],
    movementType: Option[MovementType],
    messageType: Option[MessageType],
    clientId: Option[ClientId],
    isTransitional: Boolean,
    versionHeader: APIVersionHeader
  )(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): Future[Unit]

}

class AuditingServiceImpl @Inject() (auditingConnector: AuditingConnector) extends AuditingService with Logging {

  override def auditMessageEvent(
    auditType: AuditType,
    contentType: String,
    contentLength: Long,
    payload: Source[ByteString, ?],
    movementId: Option[MovementId],
    messageId: Option[MessageId],
    enrolmentEORI: Option[EoriNumber],
    movementType: Option[MovementType],
    messageType: Option[MessageType],
    clientId: Option[ClientId],
    isTransitional: Boolean,
    versionHeader: APIVersionHeader
  )(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): Future[Unit] =
    auditingConnector
      .postMessageType(
        auditType,
        contentType,
        contentLength,
        payload,
        movementId,
        messageId,
        enrolmentEORI,
        movementType,
        messageType,
        clientId,
        isTransitional,
        versionHeader
      )
      .recover { case NonFatal(e) =>
        logger.warn("Unable to audit payload due to an exception", e)
      }

  def auditStatusEvent(
    auditType: AuditType,
    payload: Option[JsValue],
    movementId: Option[MovementId],
    messageId: Option[MessageId],
    enrolmentEORI: Option[EoriNumber],
    movementType: Option[MovementType],
    messageType: Option[MessageType],
    clientId: Option[ClientId],
    versionHeader: APIVersionHeader
  )(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): Future[Unit] =
    auditingConnector.postStatus(auditType, payload, movementId, messageId, enrolmentEORI, movementType, messageType, clientId, versionHeader).recover {
      case NonFatal(e) =>
        logger.warn("Unable to audit payload due to an exception", e)

    }
}
