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
import uk.gov.hmrc.transitmovementsrouter.models.errors.SDESError
import uk.gov.hmrc.transitmovementsrouter.models.ConversationId
import uk.gov.hmrc.transitmovementsrouter.models.MessageId
import uk.gov.hmrc.transitmovementsrouter.models.MovementId
import uk.gov.hmrc.transitmovementsrouter.models.sdes.SdesAudit
import uk.gov.hmrc.transitmovementsrouter.models.sdes.SdesChecksum
import uk.gov.hmrc.transitmovementsrouter.models.sdes.SdesFile
import uk.gov.hmrc.transitmovementsrouter.models.sdes.SdesFilereadyRequest
import uk.gov.hmrc.transitmovementsrouter.models.sdes.SdesProperties

import java.util.Base64
import java.util.UUID
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.control.NonFatal

@ImplementedBy(classOf[SDESServiceImpl])
trait SDESService {

  def send(movementId: MovementId, messageId: MessageId, objectStoreSummary: ObjectSummaryWithMd5)(implicit
    ec: ExecutionContext,
    hc: HeaderCarrier
  ): EitherT[Future, SDESError, Unit]
}

class SDESServiceImpl @Inject() (appConfig: AppConfig, sDESConnector: SDESConnector) extends SDESService {

  private lazy val srn             = appConfig.sdesSrn
  private lazy val informationType = appConfig.sdesInformationType

  def send(movementId: MovementId, messageId: MessageId, objectStoreSummary: ObjectSummaryWithMd5)(implicit
    ec: ExecutionContext,
    hc: HeaderCarrier
  ): EitherT[Future, SDESError, Unit] = EitherT {

    val file = SdesFile(
      srn,
      objectStoreSummary.location.fileName,
      s"${appConfig.objectStoreUrl}/${objectStoreSummary.location.asUri}",
      SdesChecksum(value = Base64.getDecoder.decode(objectStoreSummary.contentMd5.value).map("%02x".format(_)).mkString),
      objectStoreSummary.contentLength,
      Seq(
        SdesProperties("x-conversation-id", ConversationId(movementId, messageId).value.toString)
      )
    )

    val request = SdesFilereadyRequest(
      informationType,
      file,
      SdesAudit(UUID.randomUUID.toString)
    )
    sDESConnector
      .send(request)
      .map(
        _ => Right(())
      )
      .recover {
        case NonFatal(e) =>
          Left(SDESError.UnexpectedError(thr = Some(e)))
      }
  }
}
