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

import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import cats.data.EitherT
import com.google.inject.ImplementedBy
import com.google.inject.Inject
import com.google.inject.Singleton
import io.lemonlabs.uri.UrlPath
import play.api.http.HeaderNames
import play.api.http.MimeTypes
import play.api.http.Status.BAD_REQUEST
import play.api.http.Status.INTERNAL_SERVER_ERROR
import play.api.http.Status.NOT_FOUND
import play.api.libs.json._
import uk.gov.hmrc.http.HttpReads.Implicits._
import uk.gov.hmrc.http.HeaderCarrier
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

  def post(movementId: MovementId, messageId: MessageId, source: Option[Source[ByteString, _]])(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext,
    mat: Materializer
  ): EitherT[Future, PushNotificationError, Unit]
}

@Singleton
class PushNotificationsConnectorImpl @Inject() (httpClientV2: HttpClientV2, appConfig: AppConfig) extends PushNotificationsConnector {

  val baseUrl           = appConfig.transitMovementsPushNotificationsUrl
  val baseRoute: String = "/transit-movements-push-notifications"

  private def pushNotificationMessageUpdate(movementId: MovementId, messageId: MessageId): UrlPath =
    UrlPath.parse(s"$baseRoute/traders/movements/${movementId.value}/messages/${messageId.value}")

  override def post(movementId: MovementId, messageId: MessageId, bodyData: Option[Source[ByteString, _]])(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext,
    mat: Materializer
  ): EitherT[Future, PushNotificationError, Unit] = {
    val result = if (!appConfig.pushNotificationsEnabled) {
      Future.successful(Right(()))
    } else {
      val url = baseUrl.withPath(pushNotificationMessageUpdate(movementId, messageId))

      val requestBuilder = httpClientV2.post(url"$url")

      val futureResponse = bodyData match {
        case None =>
          requestBuilder.execute[Either[UpstreamErrorResponse, HttpResponse]]
        case Some(body) =>
          val chunksFuture = body.runFold("")(
            (acc, chunk) => acc + chunk.utf8String
          )
          chunksFuture.flatMap {
            chunks =>
              requestBuilder
                .transform(_.addHttpHeaders(HeaderNames.CONTENT_TYPE -> MimeTypes.XML))
                .withBody(Json.obj("chunks" -> Seq(chunks)))
                .execute[Either[UpstreamErrorResponse, HttpResponse]]
          }
      }

      futureResponse.map {
        case Right(_) =>
          Right(())
        case Left(error) =>
          error.statusCode match {
            case NOT_FOUND             => Left(MovementNotFound(movementId))
            case INTERNAL_SERVER_ERROR => Left(Unexpected(Some(error.getCause)))
            case _                     => Left(Unexpected(Some(error.getCause)))
          }
      }
    }

    EitherT(result.recover {
      case NonFatal(ex) => Left(Unexpected(Some(ex)))
    })
  }

  private def execute(requestBuilder: RequestBuilder, movementId: MovementId)(implicit hc: HeaderCarrier, ec: ExecutionContext) =
    requestBuilder
      .execute[Either[UpstreamErrorResponse, HttpResponse]]
      .map {
        case Right(res) if res.body.length > 0 =>
          Json
            .fromJson[Unit](res.json)(
              OFormat[Unit](
                Reads {
                  _ => JsSuccess(())
                }: Reads[Unit],
                OWrites {
                  _ => Json.obj()
                }: OWrites[Unit]
              )
            )
            .map(Right(_))
            .getOrElse(Left(Unexpected(Some(new Exception("Unexpected response from push notifications service")))))
        case Right(_) =>
          Right(())
        case Left(error) =>
          error.statusCode match {
            case BAD_REQUEST           => Left(Unexpected(Some(new Exception("Bad request"))))
            case NOT_FOUND             => Left(MovementNotFound(movementId))
            case INTERNAL_SERVER_ERROR => Left(Unexpected(Some(new Exception("Internal server error"))))
            case _                     => Left(Unexpected(Some(new Exception("Unexpected response from push notifications service"))))
          }
      }
      .recover {
        case NonFatal(ex) => Left(Unexpected(Some(ex)))
      }
}
