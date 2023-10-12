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

import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.google.inject.ImplementedBy
import com.google.inject.Inject
import com.kenshoo.play.metrics.Metrics
import io.lemonlabs.uri.UrlPath
import play.api.Logging
import play.api.http.HeaderNames
import play.api.http.Status.ACCEPTED
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.HttpResponse
import uk.gov.hmrc.http.StringContextOps
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.transitmovementsrouter.config.AppConfig
import uk.gov.hmrc.transitmovementsrouter.metrics.HasMetrics
import uk.gov.hmrc.transitmovementsrouter.metrics.MetricsKeys
import uk.gov.hmrc.transitmovementsrouter.models.AuditType
import uk.gov.hmrc.transitmovementsrouter.models.EoriNumber
import uk.gov.hmrc.transitmovementsrouter.models.MessageId
import uk.gov.hmrc.transitmovementsrouter.models.MessageType
import uk.gov.hmrc.transitmovementsrouter.models.MovementId
import uk.gov.hmrc.transitmovementsrouter.models.MovementType

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.control.NonFatal

@ImplementedBy(classOf[AuditingConnectorImpl])
trait AuditingConnector {

  def post(
    auditType: AuditType,
    contentType: String,
    contentLength: Long,
    payload: Source[ByteString, _],
    movementId: Option[MovementId],
    messageId: Option[MessageId],
    enrolmentEORI: Option[EoriNumber],
    movementType: Option[MovementType],
    messageType: Option[MessageType]
  )(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): Future[Unit]

}

class AuditingConnectorImpl @Inject() (httpClient: HttpClientV2, val metrics: Metrics)(implicit appConfig: AppConfig)
    extends AuditingConnector
    with BaseConnector
    with HasMetrics
    with Logging {

  val auditingBaseRoute: String = "/transit-movements-auditing"

  def post(
    auditType: AuditType,
    contentType: String,
    contentLength: Long,
    payload: Source[ByteString, _],
    movementId: Option[MovementId] = None,
    messageId: Option[MessageId] = None,
    enrolmentEORI: Option[EoriNumber] = None,
    movementType: Option[MovementType] = None,
    messageType: Option[MessageType] = None
  )(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): Future[Unit] = withMetricsTimerAsync(MetricsKeys.AuditingBackend.Post) {
    _ =>
      val url = appConfig.auditingUrl.withPath(auditingRoute(auditType))

      val path = hc.otherHeaders
        .collectFirst {
          case ("path", value) => value
        }
        .getOrElse("-")
      httpClient
        .post(url"$url")
        .withInternalAuthToken
        .withMovementId(movementId)
        .withMessageId(messageId)
        .withEoriNumber(enrolmentEORI)
        .withMovementType(movementType)
        .withAuditMessageType(messageType)
        .setHeader(
          HeaderNames.CONTENT_TYPE -> contentType,
          "X-ContentLength"        -> contentLength.toString,
          "X-Audit-Meta-Path"      -> path,
          "X-Audit-Source"         -> "transit-movements-router"
        )
        .withBody(payload)
        .execute[HttpResponse]
        .flatMap {
          response =>
            response.status match {
              case ACCEPTED => Future.successful(())
              case _        => response.error
            }
        }
        .recover {
          case NonFatal(thr) => Future.failed(thr)
        }
  }

  def auditingRoute(auditType: AuditType): UrlPath = UrlPath.parse(s"$auditingBaseRoute/audit/${auditType.name}")

}
