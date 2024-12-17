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

import com.google.inject.ImplementedBy
import com.google.inject.Inject
import com.google.inject.Singleton
import play.api.Logging
import play.api.http.Status.NO_CONTENT
import play.api.libs.json.Json
import play.api.libs.ws.JsonBodyWritables
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.HttpReads.Implicits.readRaw
import uk.gov.hmrc.http.HttpResponse
import uk.gov.hmrc.http.StringContextOps
import uk.gov.hmrc.http.UpstreamErrorResponse
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.transitmovementsrouter.config.AppConfig
import uk.gov.hmrc.transitmovementsrouter.models.ConversationId
import uk.gov.hmrc.transitmovementsrouter.models.MessageId
import uk.gov.hmrc.transitmovementsrouter.models.MovementId
import uk.gov.hmrc.transitmovementsrouter.models.errors.SDESError
import uk.gov.hmrc.transitmovementsrouter.models.sdes.FileMd5Checksum
import uk.gov.hmrc.transitmovementsrouter.models.sdes.FileName
import uk.gov.hmrc.transitmovementsrouter.models.sdes.FileSize
import uk.gov.hmrc.transitmovementsrouter.models.sdes.FileURL
import uk.gov.hmrc.transitmovementsrouter.models.sdes.SdesAudit
import uk.gov.hmrc.transitmovementsrouter.models.sdes.SdesChecksum
import uk.gov.hmrc.transitmovementsrouter.models.sdes.SdesFile
import uk.gov.hmrc.transitmovementsrouter.models.sdes.SdesFilereadyRequest
import uk.gov.hmrc.transitmovementsrouter.models.sdes.SdesProperties
import uk.gov.hmrc.transitmovementsrouter.utils.RouterHeaderNames
import uk.gov.hmrc.transitmovementsrouter.utils.UUIDGenerator

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.control.NonFatal

@ImplementedBy(classOf[SDESConnectorImpl])
trait SDESConnector {

  def send(movementId: MovementId, messageId: MessageId, fileName: FileName, fileLocation: FileURL, hash: FileMd5Checksum, size: FileSize)(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): Future[Either[SDESError, Unit]]
}

@Singleton
class SDESConnectorImpl @Inject() (httpClientV2: HttpClientV2, appConfig: AppConfig, uuidGenerator: UUIDGenerator)
    extends SDESConnector
    with JsonBodyWritables
    with Logging {

  private lazy val sdesFileReadyUrl =
    appConfig.sdesServiceBaseUrl.withPath(appConfig.sdesFileReadyUri)

  private lazy val clientId        = appConfig.sdesClientId
  private lazy val srn             = appConfig.sdesSrn
  private lazy val informationType = appConfig.sdesInformationType

  private def logMessage(request: SdesFilereadyRequest, message: String) = {
    val properties = request.file.properties
      .map(
        prop => s"  ${prop.name}: ${prop.value}"
      )
      .mkString(System.lineSeparator())
    s"""|Posting NCTS message via SDES:
        |
        |Correlation ID: ${request.audit.correlationID}
        |File name: ${request.file.name.value}
        |File location: ${request.file.location.value}
        |File checksum ${request.file.checksum.algorithm}: ${request.file.checksum.value}
        |File size: ${request.file.size.value} bytes
        |Properties:
        |$properties
        |$message""".stripMargin
  }

  def send(movementId: MovementId, messageId: MessageId, fileName: FileName, fileLocation: FileURL, hash: FileMd5Checksum, size: FileSize)(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): Future[Either[SDESError, Unit]] = {

    val request = SdesFilereadyRequest(
      informationType,
      SdesFile(
        srn,
        fileName,
        fileLocation,
        SdesChecksum(hash),
        size,
        Seq(
          SdesProperties(RouterHeaderNames.CONVERSATION_ID.toLowerCase, ConversationId(movementId, messageId).value.toString)
        )
      ),
      SdesAudit(uuidGenerator.generateUUID().toString)
    )

    httpClientV2
      .post(url"$sdesFileReadyUrl")
      .setHeader(RouterHeaderNames.CLIENT_ID -> clientId)
      .withBody(Json.toJson(request))
      .execute[HttpResponse]
      .flatMap {
        response =>
          val logEntry = logMessage(request, s"Response status: ${response.status}")
          response.status match {
            case NO_CONTENT =>
              logger.info(logEntry)
              Future.successful(Right(()))
            case _ =>
              logger.error(logEntry)
              Future.successful(Left(SDESError.UnexpectedError(Some(UpstreamErrorResponse(response.body, response.status)))))
          }
      }
      .recoverWith {
        case NonFatal(e) =>
          logger.error(logMessage(request, s"Request Error: ${e.getMessage}"), e)
          Future.successful(Left(SDESError.UnexpectedError(Some(e))))
      }
  }

}
