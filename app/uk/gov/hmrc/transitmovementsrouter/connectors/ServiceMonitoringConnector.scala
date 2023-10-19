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

package uk.gov.hmrc.transitmovementsrouter.connectors

import com.google.inject.ImplementedBy
import com.google.inject.Inject
import play.api.libs.json.JsValue
import play.api.libs.json.Json
import play.api.libs.ws.BodyWritable
import play.api.libs.ws.JsonBodyWritables
import play.mvc.Http.MimeTypes
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.HttpReadsInstances._
import uk.gov.hmrc.http.UpstreamErrorResponse
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.transitmovementsrouter.config.AppConfig
import uk.gov.hmrc.transitmovementsrouter.models.ConversationId
import uk.gov.hmrc.transitmovementsrouter.models.CustomsOffice
import uk.gov.hmrc.transitmovementsrouter.models.MessageId
import uk.gov.hmrc.transitmovementsrouter.models.MessageType
import uk.gov.hmrc.transitmovementsrouter.models.MovementId
import uk.gov.hmrc.transitmovementsrouter.models.requests.IncomingServiceMonitoringRequest
import uk.gov.hmrc.transitmovementsrouter.models.requests.OutgoingServiceMonitoringRequest
import uk.gov.hmrc.transitmovementsrouter.models.requests.ServiceMonitoringRequests.incomingWrites
import uk.gov.hmrc.transitmovementsrouter.models.requests.ServiceMonitoringRequests.outgoingWrites

import java.net.URL
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneOffset
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

@ImplementedBy(classOf[ServiceMonitoringConnectorImpl])
trait ServiceMonitoringConnector {

  def outgoing(movementId: MovementId, messageId: MessageId, messageType: MessageType, office: CustomsOffice)(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): Future[Unit]

  def incoming(movementId: MovementId, messageId: MessageId, messageType: MessageType)(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Unit]

}

class ServiceMonitoringConnectorImpl @Inject() (appConfig: AppConfig, httpClient: HttpClientV2, clock: Clock) extends ServiceMonitoringConnector {

  private lazy val outgoingUrl = new URL(s"${appConfig.serviceMonitoringUrl}${appConfig.serviceMonitoringOutgoingUri}")
  private lazy val incomingUrl = new URL(s"${appConfig.serviceMonitoringUrl}${appConfig.serviceMonitoringIncomingUri}")

  // The NCTS monitoring service expects a content type of text/plain, so this implicit enforces it
  implicit private val serviceMonitoringBodyWritable: BodyWritable[JsValue] = BodyWritable(JsonBodyWritables.writeableOf_JsValue.transform, MimeTypes.TEXT)

  override def outgoing(movementId: MovementId, messageId: MessageId, messageType: MessageType, office: CustomsOffice)(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): Future[Unit] = {
    val conversationId = ConversationId(movementId, messageId)
    httpClient
      .post(outgoingUrl)
      .setHeader("X-Message-Sender" -> conversationId.value.toString)
      .withBody(
        Json.toJson(
          OutgoingServiceMonitoringRequest(
            conversationId,
            LocalDateTime.now(clock.withZone(ZoneOffset.UTC)),
            messageType,
            office
          )
        )
      )
      .execute[Either[UpstreamErrorResponse, Unit]]
      .flatMap {
        case Right(x)    => Future.successful((): Unit)
        case Left(error) => Future.failed(error)
      }
  }

  override def incoming(movementId: MovementId, messageId: MessageId, messageType: MessageType)(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): Future[Unit] =
    httpClient
      .post(incomingUrl)
      .withBody(
        Json.toJson(
          IncomingServiceMonitoringRequest(
            ConversationId(movementId, messageId),
            LocalDateTime.now(clock.withZone(ZoneOffset.UTC)),
            messageType
          )
        )
      )
      .execute[Either[UpstreamErrorResponse, Unit]]
      .flatMap {
        case Right(x)    => Future.successful((): Unit)
        case Left(error) => Future.failed(error)
      }
}
