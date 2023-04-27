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
import com.google.inject.ImplementedBy
import com.google.inject.Inject
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.objectstore.client.ObjectSummaryWithMd5
import uk.gov.hmrc.transitmovementsrouter.config.AppConfig
import uk.gov.hmrc.transitmovementsrouter.connectors.SDESConnector
import uk.gov.hmrc.transitmovementsrouter.models.MessageId
import uk.gov.hmrc.transitmovementsrouter.models.MovementId
import uk.gov.hmrc.transitmovementsrouter.models.errors.SDESError
import uk.gov.hmrc.transitmovementsrouter.models.sdes.FileMd5Checksum
import uk.gov.hmrc.transitmovementsrouter.models.sdes.FileName
import uk.gov.hmrc.transitmovementsrouter.models.sdes.FileSize
import uk.gov.hmrc.transitmovementsrouter.models.sdes.FileURL

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

@ImplementedBy(classOf[SDESServiceImpl])
trait SDESService {

  def send(movementId: MovementId, messageId: MessageId, objectStoreSummary: ObjectSummaryWithMd5)(implicit
    ec: ExecutionContext,
    hc: HeaderCarrier
  ): EitherT[Future, SDESError, Unit]
}

class SDESServiceImpl @Inject() (appConfig: AppConfig, sdesConnector: SDESConnector) extends SDESService {

  def send(movementId: MovementId, messageId: MessageId, objectStoreSummary: ObjectSummaryWithMd5)(implicit
    ec: ExecutionContext,
    hc: HeaderCarrier
  ): EitherT[Future, SDESError, Unit] = EitherT {
    sdesConnector.send(
      movementId,
      messageId,
      FileName(objectStoreSummary.location),
      FileURL(objectStoreSummary.location, appConfig.objectStoreUrl),
      FileMd5Checksum.fromBase64(objectStoreSummary.contentMd5),
      FileSize(objectStoreSummary.contentLength)
    )
  }
}
