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

import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import com.google.inject.ImplementedBy
import com.google.inject.Inject
import uk.gov.hmrc.play.bootstrap.metrics.Metrics
import io.lemonlabs.uri.Url
import io.lemonlabs.uri.UrlPath
import play.api.Logging
import play.api.http.HeaderNames
import play.api.http.MimeTypes
import play.api.http.Status.ACCEPTED
import play.api.libs.json.JsObject
import play.api.libs.json.JsValue
import play.api.libs.json.Json
import play.api.libs.ws.DefaultBodyWritables
import play.api.libs.ws.JsonBodyWritables
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.HttpResponse
import uk.gov.hmrc.http.StringContextOps
import uk.gov.hmrc.http.UpstreamErrorResponse
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.transitmovementsrouter.config.AppConfig
import uk.gov.hmrc.transitmovementsrouter.metrics.HasMetrics
import uk.gov.hmrc.transitmovementsrouter.metrics.MetricsKeys
import uk.gov.hmrc.transitmovementsrouter.models.AuditType
import uk.gov.hmrc.transitmovementsrouter.models.ClientId
import uk.gov.hmrc.transitmovementsrouter.models.EoriNumber
import uk.gov.hmrc.transitmovementsrouter.models.MessageId
import uk.gov.hmrc.transitmovementsrouter.models.MessageType
import uk.gov.hmrc.transitmovementsrouter.models.MovementId
import uk.gov.hmrc.transitmovementsrouter.models.MovementType
import uk.gov.hmrc.transitmovementsrouter.models.requests.Details
import uk.gov.hmrc.transitmovementsrouter.models.requests.Metadata
import uk.gov.hmrc.http.HttpReads.Implicits.*

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.control.NonFatal

@ImplementedBy(classOf[AuditingConnectorImpl])
trait AuditingConnector {

  def postMessageType(
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
    isTransitional: Boolean
  )(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): Future[Unit]

  def postStatus(
    auditType: AuditType,
    payload: Option[JsValue],
    movementId: Option[MovementId],
    messageId: Option[MessageId],
    enrolmentEORI: Option[EoriNumber],
    movementType: Option[MovementType],
    messageType: Option[MessageType],
    clientId: Option[ClientId]
  )(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): Future[Unit]

}

class AuditingConnectorImpl @Inject() (httpClient: HttpClientV2, val metrics: Metrics)(implicit appConfig: AppConfig)
    extends AuditingConnector
    with DefaultBodyWritables
    with JsonBodyWritables
    with BaseConnector
    with HasMetrics
    with Logging {

  private val auditingBaseRoute: String = "/transit-movements-auditing"

  def postMessageType(
    auditType: AuditType,
    contentType: String,
    contentLength: Long,
    payload: Source[ByteString, ?],
    movementId: Option[MovementId] = None,
    messageId: Option[MessageId] = None,
    enrolmentEORI: Option[EoriNumber] = None,
    movementType: Option[MovementType] = None,
    messageType: Option[MessageType] = None,
    clientId: Option[ClientId] = None,
    isTransitional: Boolean
  )(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): Future[Unit] = withMetricsTimerAsync(MetricsKeys.AuditingBackend.Post) {
    _ =>
      val (url: Url, path: String) = getUrlAndPath(auditType, hc)

      val originalHeaders: Seq[(String, String)] = Seq(
        HeaderNames.CONTENT_TYPE -> contentType,
        "X-ContentLength"        -> contentLength.toString,
        "X-Audit-Meta-Path"      -> path,
        "X-Audit-Source"         -> "transit-movements-router"
      )

      val allHeaders: Seq[(String, String)] = originalHeaders

      httpClient
        .post(url"$url")
        .withInternalAuthToken
        .withMovementId(movementId)
        .withMessageId(messageId)
        .withEoriNumber(enrolmentEORI)
        .withMovementType(movementType)
        .withAuditMessageType(messageType)
        .withClientId(clientId)
        .setHeader(
          allHeaders *
        )
        .withBody(payload)
        .execute[HttpResponse]
        .flatMap(handleResponse)
        .recover {
          case NonFatal(thr) => Future.failed(thr)
        }
  }

  override def postStatus(
    auditType: AuditType,
    payload: Option[JsValue],
    movementId: Option[MovementId],
    messageId: Option[MessageId],
    enrolmentEORI: Option[EoriNumber],
    movementType: Option[MovementType],
    messageType: Option[MessageType],
    clientId: Option[ClientId]
  )(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): Future[Unit] = withMetricsTimerAsync(MetricsKeys.AuditingBackend.Post) {
    _ =>
      val (url: Url, path: String) = getUrlAndPath(auditType, hc)
      val metadata                 = Metadata(path, movementId, messageId, enrolmentEORI, movementType, messageType)
      val details                  = Details(metadata, payload.map(_.as[JsObject]))
      httpClient
        .post(url"$url")
        .withInternalAuthToken
        .withClientId(clientId)
        .setHeader(
          "X-Audit-Source"         -> "transit-movements-router",
          HeaderNames.CONTENT_TYPE -> MimeTypes.JSON
        )
        .withBody(Json.toJson(details))
        .execute[HttpResponse]
        .flatMap(handleResponse)
        .recover {
          case NonFatal(thr) => Future.failed(thr)
        }
  }

  private def handleResponse(response: HttpResponse): Future[Unit] =
    response.status match {
      case ACCEPTED => Future.successful(())
      case _        => Future.failed(UpstreamErrorResponse(response.body, response.status))
    }

  private def getUrlAndPath(auditType: AuditType, hc: HeaderCarrier): (Url, String) = {
    val auditRoute = UrlPath.parse(s"$auditingBaseRoute/audit/${auditType.name}")
    val url        = appConfig.auditingUrl.withPath(auditRoute).toUrl
    val path = hc.otherHeaders
      .collectFirst {
        case ("path", value) => value
      }
      .getOrElse("-")
    (url, path)
  }

}
