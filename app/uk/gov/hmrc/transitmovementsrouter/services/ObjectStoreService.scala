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

import akka.NotUsed
import akka.stream.scaladsl.Source
import akka.util.ByteString
import cats.data.EitherT
import com.google.inject.ImplementedBy
import play.api.Logging
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.objectstore.client.ObjectSummaryWithMd5
import uk.gov.hmrc.objectstore.client.Path
import uk.gov.hmrc.objectstore.client.play.Implicits._
import uk.gov.hmrc.objectstore.client.play.PlayObjectStoreClientEither
import uk.gov.hmrc.transitmovementsrouter.models.errors.ObjectStoreError
import uk.gov.hmrc.transitmovementsrouter.models.responses.UpscanResponse.DownloadUrl
import uk.gov.hmrc.transitmovementsrouter.models._

import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Try
import scala.util.control.NonFatal

@ImplementedBy(classOf[ObjectStoreServiceImpl])
trait ObjectStoreService {

  def addMessage(
    upscanUrl: DownloadUrl,
    movementId: MovementId,
    messageId: MessageId,
    objectStoreFileDirectory: ObjectStoreFileDirectory,
    objectStoreOwner: ObjectStoreOwner,
    retentionPeriod: ObjectStoreRetentionPeriod
  )(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): EitherT[Future, ObjectStoreError, ObjectSummaryWithMd5]

  def getObjectStoreFile(objectStoreResourceLocation: ObjectStoreResourceLocation)(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): EitherT[Future, ObjectStoreError, Source[ByteString, _]]

}

@Singleton
class ObjectStoreServiceImpl @Inject() (client: PlayObjectStoreClientEither) extends ObjectStoreService with Logging {

  override def addMessage(
    upscanUrl: DownloadUrl,
    movementId: MovementId,
    messageId: MessageId,
    objectStoreFileDirectory: ObjectStoreFileDirectory,
    objectStoreOwner: ObjectStoreOwner,
    retentionPeriod: ObjectStoreRetentionPeriod
  )(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): EitherT[Future, ObjectStoreError, ObjectSummaryWithMd5] =
    EitherT {

      (for {
        url <- Future.fromTry(Try(new URL(upscanUrl.value)))
        response <- client
          .uploadFromUrl(
            from = url,
            to = objectStoreFileDirectory.value,
            owner = objectStoreOwner.value,
            retentionPeriod = retentionPeriod.value
          )
          .map {
            case Right(load) => Right(load)
            case Left(thr)   => Left(ObjectStoreError.UnexpectedError(thr = Some(thr)))
          }
      } yield response).recover {
        case NonFatal(thr) => Left(ObjectStoreError.UnexpectedError(thr = Some(thr)))
      }
    }

  override def getObjectStoreFile(objectStoreResourceLocation: ObjectStoreResourceLocation)(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): EitherT[Future, ObjectStoreError, Source[ByteString, _]] =
    EitherT(
      client
        .getObject[Source[ByteString, NotUsed]](
          Path.File(objectStoreResourceLocation.value),
          "common-transit-conversion-traders"
        )
        .map {
          case Right(Some(source)) => Right(source.content)
          case _                   => Left(ObjectStoreError.FileNotFound(objectStoreResourceLocation.value))
        }
        .recover {
          case NonFatal(ex) => Left(ObjectStoreError.UnexpectedError(Some(ex)))
        }
    )
}
