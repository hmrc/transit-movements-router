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
import uk.gov.hmrc.objectstore.client.Md5Hash
import uk.gov.hmrc.objectstore.client.ObjectMetadata
import uk.gov.hmrc.objectstore.client.ObjectSummaryWithMd5
import uk.gov.hmrc.objectstore.client.RetentionPeriod
import uk.gov.hmrc.transitmovementsrouter.models.MessageId
import uk.gov.hmrc.transitmovementsrouter.models.MovementId

import java.io.File
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

trait Path[A] {
  def path: String
}

object Path {
  case class Directory(path: String) extends Path[Directory]

  case class File(parent: Path[Directory], filename: String) extends Path[File] {
    def path: String = parent.path + "/" + filename
  }
}

trait ObjectStoreContentWrite[F[_], CONTENT, BODY] {
  def writeContent(content: CONTENT, contentType: Option[String], contentMd5: Option[Md5Hash]): F[BODY]
}

trait PlayObjectStoreClientEither[F[_]] {

  def putObject[BODY](
    file: Path[Path.File],
    ioFile: File,
    retentionPeriod: RetentionPeriod,
    maybeContentEncoding: Option[String],
    maybeContentMd5: Option[Md5Hash]
  )(
    contentWriter: ObjectStoreContentWrite[F, File, BODY],
    hc: HeaderCarrier
  )(implicit ec: ExecutionContext): EitherT[F, Throwable, ObjectSummaryWithMd5]
}

@Singleton
class ObjectStoreConnector @Inject() (
  client: PlayObjectStoreClientEither[Future]
)(implicit ec: ExecutionContext) {

  def upload(movementId: MovementId, messageId: MessageId, path: java.nio.file.Path)(implicit
    hc: HeaderCarrier
  ): EitherT[Future, Result, ObjectSummaryWithMd5] = {
    val objectMetadata = new ObjectMetadata("", 0, new Md5Hash("md5Hash"), Instant.now(), Map.empty)

    val directory = Path.Directory(s"movements/${movementId.value}/messages")

    val file = Path.File(directory, s"${messageId.value}.xml")
    val metadata = ObjectMetadata(
      contentType = "application/xml",
      contentLength = path.toFile.length(),
      contentMd5 = new Md5Hash("md5-hash-string"),
      lastModified = Instant.now(),
      userMetadata = Map.empty[String, String]
    )

    client
      .putObject[Array[Byte]](
        file = file,
        ioFile = path.toFile,
        retentionPeriod = RetentionPeriod.OneMonth,
        maybeContentEncoding = Some(metadata.contentType),
        maybeContentMd5 = Some(metadata.contentMd5)
      )(
        contentWriter = new ObjectStoreContentWrite[Future, File, Array[Byte]] {
          override def writeContent(content: File, contentType: Option[String], contentMd5: Option[Md5Hash]): Future[Array[Byte]] =
            Future.successful(Array.emptyByteArray)
        },
        hc = hc
      )
      .leftMap(
        _ => Results.InternalServerError("An error has occurred.")
      )
  }
}
