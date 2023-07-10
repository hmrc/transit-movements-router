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
import cats.data.EitherT
import com.google.inject.ImplementedBy
import com.google.inject.Inject
import com.google.inject.Singleton
import io.lemonlabs.uri.UrlPath
import play.api.http.HeaderNames
import play.api.http.MimeTypes
import play.api.http.Status.ACCEPTED
import play.api.http.Status.NOT_FOUND
import play.api.libs.json.JsValue
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.HttpReads.Implicits._
import uk.gov.hmrc.http.HttpResponse
import uk.gov.hmrc.http.StringContextOps
import uk.gov.hmrc.http.UpstreamErrorResponse
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.client.RequestBuilder
import uk.gov.hmrc.transitmovementsrouter.config.AppConfig
import uk.gov.hmrc.transitmovementsrouter.models._
import uk.gov.hmrc.transitmovementsrouter.models.errors.PushNotificationError
import uk.gov.hmrc.transitmovementsrouter.models.errors.PushNotificationError.MovementNotFound
import uk.gov.hmrc.transitmovementsrouter.models.errors.PushNotificationError.Unexpected

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.control.NonFatal

@ImplementedBy(classOf[PushNotificationsConnectorImpl])
trait PushNotificationsConnector {

  def postMessageReceived(movementId: MovementId, messageId: MessageId, messageType: MessageType, sourceXml: Source[ByteString, _])(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): EitherT[Future, PushNotificationError, Unit]

  def postSubmissionNotification(movementId: MovementId, messageId: MessageId, sourceJson: JsValue)(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): EitherT[Future, PushNotificationError, Unit]

  def postMessageReceived(movementId: MovementId, messageId: MessageId, messageType: MessageType)(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): EitherT[Future, PushNotificationError, Unit]
}

@Singleton
class PushNotificationsConnectorImpl @Inject() (httpClientV2: HttpClientV2)(implicit appConfig: AppConfig)
    extends PushNotificationsConnector
    with BaseConnector {

  val baseUrl           = appConfig.transitMovementsPushNotificationsUrl
  val baseRoute: String = "/transit-movements-push-notifications"

  private def pushNotificationMessageUpdate(movementId: MovementId, messageId: MessageId, notificationType: String): UrlPath =
    UrlPath.parse(s"$baseRoute/traders/movements/${movementId.value}/messages/${messageId.value}/$notificationType")

  override def postMessageReceived(movementId: MovementId, messageId: MessageId, messageType: MessageType, sourceXml: Source[ByteString, _])(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): EitherT[Future, PushNotificationError, Unit] =
    EitherT {
      if (!appConfig.pushNotificationsEnabled) {
        Future.successful(Right(()))
      } else {
        val request = createRequest(movementId, messageId, "messageReceived")
          .setHeader(HeaderNames.CONTENT_TYPE -> MimeTypes.XML)
          .withBody(sourceXml)
        executeAndExpect(movementId, Option(messageType), request, ACCEPTED)
      }
    }

  override def postSubmissionNotification(movementId: MovementId, messageId: MessageId, sourceJson: JsValue)(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): EitherT[Future, PushNotificationError, Unit] =
    EitherT {
      if (!appConfig.pushNotificationsEnabled) {
        Future.successful(Right(()))
      } else {
        val request = createRequest(movementId, messageId, "submissionNotification")
          .setHeader(HeaderNames.CONTENT_TYPE -> MimeTypes.JSON)
          .withBody(sourceJson)
        executeAndExpect(movementId, None, request, ACCEPTED)

      }
    }

  override def postMessageReceived(movementId: MovementId, messageId: MessageId, messageType: MessageType)(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): EitherT[Future, PushNotificationError, Unit] =
    EitherT {
      if (!appConfig.pushNotificationsEnabled) {
        Future.successful(Right(()))
      } else {
        executeAndExpect(movementId, Option(messageType), createRequest(movementId, messageId, "messageReceived"), ACCEPTED)

      }
    }

  private def executeAndExpect(movementId: MovementId, messageType: Option[MessageType], requestBuilder: RequestBuilder, expected: Int)(implicit
    ec: ExecutionContext
  ): Future[Either[PushNotificationError, Unit]] =
    requestBuilder.withInternalAuthToken
      .withMessageType(messageType)
      .execute[HttpResponse]
      .map {
        response =>
          response.status match {
            case `expected` => Right((): Unit)
            case NOT_FOUND  => Left(MovementNotFound(movementId))
            case _          => Left(Unexpected(Some(UpstreamErrorResponse(response.body, response.status))))
          }
      }
      .recover {
        case NonFatal(ex) => Left(Unexpected(Some(ex)))
      }

  private def createRequest(movementId: MovementId, messageId: MessageId, notificationType: String)(implicit hc: HeaderCarrier) = {
    val url = baseUrl.withPath(pushNotificationMessageUpdate(movementId, messageId, notificationType))
    httpClientV2.post(url"$url")
  }
}
