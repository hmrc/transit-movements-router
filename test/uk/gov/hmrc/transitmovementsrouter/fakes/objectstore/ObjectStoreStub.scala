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

package uk.gov.hmrc.transitmovementsrouter.fakes.objectstore

import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.objectstore.client.Path.File
import uk.gov.hmrc.objectstore.client.config.ObjectStoreClientConfig
import uk.gov.hmrc.objectstore.client.http.ObjectStoreContentRead
import uk.gov.hmrc.objectstore.client.play.test.stub.StubPlayObjectStoreClientEither
import uk.gov.hmrc.objectstore.client.play.FutureEither
import uk.gov.hmrc.objectstore.client.play.ResBody
import uk.gov.hmrc.objectstore.client.Md5Hash
import uk.gov.hmrc.objectstore.client.Object
import uk.gov.hmrc.objectstore.client.ObjectMetadata
import uk.gov.hmrc.objectstore.client.ObjectSummaryWithMd5
import uk.gov.hmrc.objectstore.client.Path
import uk.gov.hmrc.objectstore.client.RetentionPeriod
import uk.gov.hmrc.transitmovementsrouter.generators.TestModelGenerators
import uk.gov.hmrc.transitmovementsrouter.models.errors.ObjectStoreError

import java.time.Instant
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

class ObjectStoreStub(config: ObjectStoreClientConfig)(implicit
  m: Materializer,
  ec: ExecutionContext
) extends StubPlayObjectStoreClientEither(config)
    with TestModelGenerators {

  override def uploadFromUrl(
    from: java.net.URL,
    to: Path.File,
    retentionPeriod: RetentionPeriod = config.defaultRetentionPeriod,
    contentType: Option[String] = None,
    contentMd5: Option[Md5Hash] = None,
    owner: String = config.owner
  )(implicit hc: HeaderCarrier): FutureEither[ObjectSummaryWithMd5] = {
    val objectSummaryWithMd5 = arbitraryObjectSummaryWithMd5.arbitrary.sample.get
    Future.successful(Right(objectSummaryWithMd5))
  }

  override def getObject[CONTENT](path: File, owner: String)(implicit
    cr: ObjectStoreContentRead[FutureEither, ResBody, CONTENT],
    hc: HeaderCarrier
  ): FutureEither[Option[Object[CONTENT]]] = {
    val filePath =
      Path.Directory("movements/movementId").file("x-conversation-id.xml")
    val metadata = ObjectMetadata("", 0, Md5Hash(""), Instant.now(), Map.empty[String, String])
    val content  = "content"
    if (filePath.asUri.equals(path.asUri)) {
      cr.readContent(Source.single(ByteString(content))).map {
        case Right(content) => Right(Option(Object(filePath, content, metadata)))
        case _              => Right(None)
      }
    } else if (path.asUri.contains("/")) {
      Future.successful(Right(None))
    } else {
      Future.failed(ObjectStoreError.UnexpectedError(None))
    }
  }

}
