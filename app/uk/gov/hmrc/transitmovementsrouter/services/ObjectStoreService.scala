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

import cats.data.EitherT
import cats.implicits._
import com.google.inject.ImplementedBy
import play.api.Logging
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.objectstore.client.ObjectSummaryWithMd5
import uk.gov.hmrc.objectstore.client.Path
import uk.gov.hmrc.objectstore.client.play.PlayObjectStoreClientEither
import uk.gov.hmrc.transitmovementsrouter.models.MessageId
import uk.gov.hmrc.transitmovementsrouter.models.MovementId
import uk.gov.hmrc.transitmovementsrouter.models.errors.ObjectStoreError
import uk.gov.hmrc.transitmovementsrouter.models.responses.UpscanResponse.DownloadUrl

import java.net.URL
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

@ImplementedBy(classOf[ObjectStoreServiceImpl])
trait ObjectStoreService {

  def addMessage(upscanUrl: DownloadUrl, movementId: MovementId, messageId: MessageId)(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): EitherT[Future, ObjectStoreError, ObjectSummaryWithMd5]

}

@Singleton
class ObjectStoreServiceImpl @Inject() (clock: Clock, client: PlayObjectStoreClientEither) extends ObjectStoreService with Logging {

  private val dateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)

  override def addMessage(upscanUrl: DownloadUrl, movementId: MovementId, messageId: MessageId)(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): EitherT[Future, ObjectStoreError, ObjectSummaryWithMd5] =
    EitherT
      .fromEither[Future] {
        Either
          .catchNonFatal(new URL(upscanUrl.value))
          .leftMap(
            e => ObjectStoreError.UnexpectedError(thr = Some(e))
          )
      }
      .flatMap {
        upscanURL =>
          val formattedDateTime = dateTimeFormatter.format(OffsetDateTime.ofInstant(clock.instant, ZoneOffset.UTC))

          EitherT(
            client
              .uploadFromUrl(
                from = upscanURL,
                to = Path.Directory("common-transit-convention-traders").file(s"${movementId.value}-${messageId.value}-$formattedDateTime.xml")
              )
              .map {
                case Left(thr)     => Left(ObjectStoreError.UnexpectedError(thr = Some(thr)))
                case Right(result) => Right(result)
              }
          )
      }
}
