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

import cats.data.EitherT
import cats.implicits._
import play.api.mvc.Result
import play.api.mvc.Results
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.objectstore.client.Path.Directory
import uk.gov.hmrc.objectstore.client.Md5Hash
import uk.gov.hmrc.objectstore.client.ObjectMetadata
import uk.gov.hmrc.objectstore.client.ObjectSummaryWithMd5
import uk.gov.hmrc.objectstore.client.RetentionPeriod
import uk.gov.hmrc.objectstore.client.play.Implicits._
import uk.gov.hmrc.objectstore.client.play.PlayObjectStoreClientEither
import uk.gov.hmrc.transitmovementsrouter.models.MessageId
import uk.gov.hmrc.transitmovementsrouter.models.MovementId

import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

@Singleton
class ObjectStoreConnector @Inject() (
  client: PlayObjectStoreClientEither
) {

  def upload(movementId: MovementId, messageId: MessageId, path: java.nio.file.Path)(implicit
    hc: HeaderCarrier
  ): EitherT[Future, Result, ObjectSummaryWithMd5] = {
    val directory = Directory(s"movements/${movementId.value}/messages").file(s"${messageId.value}.xml")
    val metadata = ObjectMetadata(
      contentType = "application/xml",
      contentLength = path.toFile.length(),
      contentMd5 = new Md5Hash("md5-hash-string"),
      lastModified = Instant.now(),
      userMetadata = Map.empty[String, String]
    )

    EitherT {
      client
        .putObject(
          directory,
          path.toFile,
          RetentionPeriod.OneMonth,
          Some(metadata.contentType),
          Some(metadata.contentMd5)
        )
        .map(_.map(Right.apply).getOrElse(Left(Results.InternalServerError("An error has occurred."))))
        .recover {
          case e: Exception => Left(Results.InternalServerError("An error has occurred."))
        }
    }
  }
}
