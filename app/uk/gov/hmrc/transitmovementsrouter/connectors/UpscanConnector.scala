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

import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import cats.data.EitherT
import cats.implicits.toBifunctorOps
import com.google.inject.ImplementedBy
import com.google.inject.Inject
import com.google.inject.Singleton
import play.api.http.Status.NOT_FOUND
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.HttpResponse
import uk.gov.hmrc.http.StringContextOps
import uk.gov.hmrc.http.UpstreamErrorResponse
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.transitmovementsrouter.models.errors.UpscanError
import uk.gov.hmrc.transitmovementsrouter.models.responses.UpscanResponse.DownloadUrl

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.control.NonFatal

@ImplementedBy(classOf[UpscanConnectorImpl])
trait UpscanConnector {

  def streamFile(url: DownloadUrl)(implicit hc: HeaderCarrier, mat: Materializer, ec: ExecutionContext): EitherT[Future, UpscanError, Source[ByteString, _]]

}

@Singleton
class UpscanConnectorImpl @Inject() (httpClientV2: HttpClientV2) extends UpscanConnector {

  override def streamFile(
    url: DownloadUrl
  )(implicit hc: HeaderCarrier, mat: Materializer, ec: ExecutionContext): EitherT[Future, UpscanError, Source[ByteString, _]] =
    EitherT {
      httpClientV2
        .get(url"${url.value}")
        .stream[Either[UpstreamErrorResponse, HttpResponse]]
        .map {
          _.map(_.bodyAsSource).leftMap {
            case UpstreamErrorResponse(_, NOT_FOUND, _, _) => UpscanError.NotFound
            case thr                                       => UpscanError.Unexpected(Some(thr))
          }
        }
        .recover {
          case NonFatal(thr) => Left(UpscanError.Unexpected(Some(thr)))
        }
    }

}
