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

package uk.gov.hmrc.transitmovementsrouter.connectors

import akka.stream.scaladsl.Source
import akka.util.ByteString
import cats.data.EitherT
import com.google.inject.ImplementedBy
import com.google.inject.Inject
import com.google.inject.Singleton
import io.lemonlabs.uri.QueryString
import io.lemonlabs.uri.Url
import io.lemonlabs.uri.UrlPath
import play.api.Logging
import play.api.http.HeaderNames
import play.api.http.MimeTypes
import play.api.http.Status.BAD_REQUEST
import play.api.http.Status.INTERNAL_SERVER_ERROR
import play.api.http.Status.NOT_FOUND
import play.api.libs.json.Json
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.HttpResponse
import uk.gov.hmrc.http.StringContextOps
import uk.gov.hmrc.http.UpstreamErrorResponse
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.transitmovementsrouter.config.AppConfig
import uk.gov.hmrc.transitmovementsrouter.models.MessageId
import uk.gov.hmrc.transitmovementsrouter.models.MessageType
import uk.gov.hmrc.transitmovementsrouter.models.MovementId
import uk.gov.hmrc.transitmovementsrouter.models.PersistenceResponse
import uk.gov.hmrc.transitmovementsrouter.models.errors.PersistenceError
import uk.gov.hmrc.transitmovementsrouter.models.errors.PersistenceError.MovementNotFound
import uk.gov.hmrc.transitmovementsrouter.models.errors.PersistenceError.Unexpected
import uk.gov.hmrc.http.HttpReads.Implicits._

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.control.NonFatal

@ImplementedBy(classOf[PersistenceConnectorImpl])
trait PersistenceConnector {

  def post(movementId: MovementId, messageId: MessageId, messageType: MessageType, source: Source[ByteString, _])(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): EitherT[Future, PersistenceError, PersistenceResponse]
}

@Singleton
class PersistenceConnectorImpl @Inject() (httpClientV2: HttpClientV2, appConfig: AppConfig) extends PersistenceConnector with Logging {

  val baseUrl           = appConfig.persistenceServiceBaseUrl
  val baseRoute: String = "/transit-movements"

  private def persistenceSendMessage(movementId: MovementId, messageId: MessageId): UrlPath =
    Url(path = s"$baseRoute/traders/movements/${movementId.value}/messages", query = QueryString.fromPairs("triggerId" -> messageId.value)).path

  override def post(movementId: MovementId, messageId: MessageId, messageType: MessageType, source: Source[ByteString, _])(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): EitherT[Future, PersistenceError, PersistenceResponse] =
    EitherT {
      val url = baseUrl.withPath(persistenceSendMessage(movementId, messageId))
      httpClientV2
        .post(url"$url")
        .addHeaders(HeaderNames.CONTENT_TYPE -> MimeTypes.XML, "X-Message-Type" -> messageType.code)
        .withBody(source)
        .execute[Either[UpstreamErrorResponse, HttpResponse]]
        .map {
          case Right(res) =>
            Json
              .fromJson[PersistenceResponse](res.json)
              .map(Right(_))
              .getOrElse(Left(Unexpected()))
          case Left(error) =>
            error.statusCode match {
              case BAD_REQUEST           => Left(Unexpected())
              case NOT_FOUND             => Left(MovementNotFound(movementId))
              case INTERNAL_SERVER_ERROR => Left(Unexpected())
            }
        }
        .recover {
          case NonFatal(ex) => Left(Unexpected(Some(ex)))
        }
    }

}
