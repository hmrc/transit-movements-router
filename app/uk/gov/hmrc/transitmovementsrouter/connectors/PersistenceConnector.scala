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
import io.lemonlabs.uri.QueryString
import io.lemonlabs.uri.Url
import io.lemonlabs.uri.UrlPath
import play.api.http.HeaderNames
import play.api.http.MimeTypes
import play.api.http.Status.BAD_REQUEST
import play.api.http.Status.INTERNAL_SERVER_ERROR
import play.api.http.Status.NOT_FOUND
import play.api.libs.json.Json
import uk.gov.hmrc.http.HttpReads.Implicits._
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.HttpResponse
import uk.gov.hmrc.http.StringContextOps
import uk.gov.hmrc.http.UpstreamErrorResponse
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.client.RequestBuilder
import uk.gov.hmrc.transitmovementsrouter.config.AppConfig
import uk.gov.hmrc.transitmovementsrouter.models.errors.PersistenceError
import uk.gov.hmrc.transitmovementsrouter.models.errors.PersistenceError.MovementNotFound
import uk.gov.hmrc.transitmovementsrouter.models.errors.PersistenceError.Unexpected
import uk.gov.hmrc.transitmovementsrouter.models._
import uk.gov.hmrc.transitmovementsrouter.utils.RouterHeaderNames

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.control.NonFatal

@ImplementedBy(classOf[PersistenceConnectorImpl])
trait PersistenceConnector {

  def postBody(
    movementId: MovementId,
    messageId: MessageId,
    messageType: MessageType,
    source: Source[ByteString, _]
  )(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): EitherT[Future, PersistenceError, PersistenceResponse]

  def postObjectStoreUri(
    movementId: MovementId,
    messageId: MessageId,
    messageType: MessageType,
    objectStoreURI: ObjectStoreURI
  )(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): EitherT[Future, PersistenceError, PersistenceResponse]
}

@Singleton
class PersistenceConnectorImpl @Inject() (httpClientV2: HttpClientV2, appConfig: AppConfig) extends PersistenceConnector {

  val baseUrl           = appConfig.persistenceServiceBaseUrl
  val baseRoute: String = "/transit-movements"

  private def persistenceSendMessage(movementId: MovementId): UrlPath =
    Url(path = s"$baseRoute/traders/movements/${movementId.value}/messages").path

  override def postBody(movementId: MovementId, messageId: MessageId, messageType: MessageType, source: Source[ByteString, _])(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): EitherT[Future, PersistenceError, PersistenceResponse] =
    EitherT {
      val request = createRequest(movementId, messageId)
        .transform(_.addHttpHeaders(HeaderNames.CONTENT_TYPE -> MimeTypes.XML, RouterHeaderNames.MESSAGE_TYPE -> messageType.code))
        .withBody(source)
      execute(request, movementId)
    }

  override def postObjectStoreUri(movementId: MovementId, messageId: MessageId, messageType: MessageType, objectStoreURI: ObjectStoreURI)(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): EitherT[Future, PersistenceError, PersistenceResponse] =
    EitherT {
      val request = createRequest(movementId, messageId).transform(
        _.addHttpHeaders(
          RouterHeaderNames.MESSAGE_TYPE     -> messageType.code,
          RouterHeaderNames.OBJECT_STORE_URI -> objectStoreURI.value
        )
      )
      execute(request, movementId)
    }

  private def execute(requestBuilder: RequestBuilder, movementId: MovementId)(implicit hc: HeaderCarrier, ec: ExecutionContext) =
    requestBuilder
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
            case _                     => Left(Unexpected())
          }
      }
      .recover {
        case NonFatal(ex) => Left(Unexpected(Some(ex)))
      }

  private def createRequest(movementId: MovementId, messageId: MessageId)(implicit hc: HeaderCarrier, ec: ExecutionContext) = {
    val url = baseUrl.withPath(persistenceSendMessage(movementId)).withQueryString(QueryString.fromPairs("triggerId" -> messageId.value))
    httpClientV2.post(url"$url")
  }

}
