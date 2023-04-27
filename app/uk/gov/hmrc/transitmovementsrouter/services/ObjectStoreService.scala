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

import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import cats.data.EitherT
import com.google.inject.ImplementedBy
import play.api.Logging
import play.mvc.Http.MimeTypes
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.objectstore.client.ObjectSummaryWithMd5
import uk.gov.hmrc.objectstore.client.Path
import uk.gov.hmrc.objectstore.client.RetentionPeriod
import uk.gov.hmrc.objectstore.client.play.Implicits._
import uk.gov.hmrc.objectstore.client.play.PlayObjectStoreClientEither
import uk.gov.hmrc.transitmovementsrouter.config.AppConfig
import uk.gov.hmrc.transitmovementsrouter.models._
import uk.gov.hmrc.transitmovementsrouter.models.errors.ObjectStoreError

import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.control.NonFatal

@ImplementedBy(classOf[ObjectStoreServiceImpl])
trait ObjectStoreService {

  def storeOutgoing(conversationId: ConversationId, stream: Source[ByteString, _])(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): EitherT[Future, ObjectStoreError, ObjectSummaryWithMd5]

}

@Singleton
class ObjectStoreServiceImpl @Inject() (clock: Clock, appConfig: AppConfig, client: PlayObjectStoreClientEither)(implicit mat: Materializer)
    extends ObjectStoreService
    with Logging {
  private val dateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)

  override def storeOutgoing(conversationId: ConversationId, stream: Source[ByteString, _])(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): EitherT[Future, ObjectStoreError, ObjectSummaryWithMd5] =
    EitherT {
      client.putObject[Source[ByteString, _]](
        Path.File(s"sdes/${conversationId.value.toString}-$dateFormat.xml"),
        stream,
        retentionPeriod = RetentionPeriod.OneWeek,
        contentType = Some(MimeTypes.XML),
        owner = "transit-movements-router",
        contentMd5 = None
      )
    }.leftMap {
      case NonFatal(thr) => ObjectStoreError.UnexpectedError(thr = Some(thr))
    }

  private def dateFormat =
    dateTimeFormatter.format(OffsetDateTime.ofInstant(clock.instant, ZoneOffset.UTC))
}
