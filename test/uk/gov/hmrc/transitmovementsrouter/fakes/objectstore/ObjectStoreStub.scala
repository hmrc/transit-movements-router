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
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.objectstore.client.Md5Hash
import uk.gov.hmrc.objectstore.client.ObjectSummaryWithMd5
import uk.gov.hmrc.objectstore.client.Path
import uk.gov.hmrc.objectstore.client.RetentionPeriod
import uk.gov.hmrc.objectstore.client.config.ObjectStoreClientConfig
import uk.gov.hmrc.objectstore.client.play.FutureEither
import uk.gov.hmrc.objectstore.client.play.test.stub.StubPlayObjectStoreClientEither
import uk.gov.hmrc.transitmovementsrouter.base.CommonGenerators

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

class ObjectStoreStub(config: ObjectStoreClientConfig)(implicit
  m: Materializer,
  ec: ExecutionContext
) extends StubPlayObjectStoreClientEither(config)
    with CommonGenerators {

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

}
