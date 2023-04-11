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
import play.api.http.Status.INTERNAL_SERVER_ERROR
import play.api.http.Status.NOT_FOUND
import play.api.libs.json.JsValue
import uk.gov.hmrc.http.HttpReads.Implicits._
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.HttpResponse
import uk.gov.hmrc.http.StringContextOps
import uk.gov.hmrc.http.UpstreamErrorResponse
import uk.gov.hmrc.http.client.HttpClientV2
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

  def post(movementId: MovementId, messageId: MessageId, sourceXml: Option[Source[ByteString, _]], sourceJson: Option[JsValue])(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): EitherT[Future, PushNotificationError, Unit]
}

@Singleton
class PushNotificationsConnectorImpl @Inject() (httpClientV2: HttpClientV2, appConfig: AppConfig) extends PushNotificationsConnector {

  val baseUrl           = appConfig.transitMovementsPushNotificationsUrl
  val baseRoute: String = "/transit-movements-push-notifications"

  private def pushNotificationMessageUpdate(movementId: MovementId, messageId: MessageId): UrlPath =
    UrlPath.parse(s"$baseRoute/traders/movements/${movementId.value}/messages/${messageId.value}")

  override def post(movementId: MovementId, messageId: MessageId, sourceXml: Option[Source[ByteString, _]], sourceJson: Option[JsValue])(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): EitherT[Future, PushNotificationError, Unit] =
    EitherT {
      if (!appConfig.pushNotificationsEnabled) {
        Future.successful(Right(()))
      } else {
        val url = baseUrl.withPath(pushNotificationMessageUpdate(movementId, messageId))

        val requestBuilder = httpClientV2.post(url"$url")

        val requestBuilderWithSource = (sourceXml, sourceJson) match {
          case (Some(xml), _)  => requestBuilder.setHeader(HeaderNames.CONTENT_TYPE -> MimeTypes.XML).withBody(xml)
          case (_, Some(json)) => requestBuilder.setHeader(HeaderNames.CONTENT_TYPE -> MimeTypes.JSON).withBody(json)
          case (_, _)          => requestBuilder
        }

        requestBuilderWithSource
          .execute[Either[UpstreamErrorResponse, HttpResponse]]
          .map {
            case Right(_) =>
              Right(())
            case Left(error) =>
              error.statusCode match {
                case NOT_FOUND             => Left(MovementNotFound(movementId))
                case INTERNAL_SERVER_ERROR => Left(Unexpected(Some(error.getCause)))
                case _                     => Left(Unexpected(Some(error.getCause)))
              }
          }
          .recover {
            case NonFatal(ex) => Left(Unexpected(Some(ex)))
          }
      }
    }
}
