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
import play.api.http.HeaderNames
import play.api.http.MimeTypes
import play.api.http.Status.NO_CONTENT
import play.api.libs.json.Json
import uk.gov.hmrc.http.HttpReads.Implicits.readRaw
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.StringContextOps
import uk.gov.hmrc.http.UpstreamErrorResponse
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.transitmovementsrouter.config.AppConfig
import uk.gov.hmrc.transitmovementsrouter.models.sdes.SdesFilereadyRequest

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

@ImplementedBy(classOf[SDESConnectorImpl])
trait SDESConnector {

  def send(request: SdesFilereadyRequest)(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): Future[Unit]
}

@Singleton
class SDESConnectorImpl @Inject() (httpClientV2: HttpClientV2, appConfig: AppConfig) extends SDESConnector with Logging {

  private lazy val sdesFileReadyUrl =
    appConfig.sdesServiceBaseUrl.withPath(appConfig.sdesFileReadyUri)
  private lazy val clientId = appConfig.sdesClientId

  def send(request: SdesFilereadyRequest)(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): Future[Unit] = {

    val logEntry =
      s"""Sending to SDES:
         |
         |${Json.stringify(Json.toJson(request))}
         |""".stripMargin
    logger.info(logEntry)

    httpClientV2
      .post(url"$sdesFileReadyUrl")
      .transform(_.addHttpHeaders(HeaderNames.CONTENT_TYPE -> MimeTypes.JSON, "X-Client-Id" -> clientId))
      .withBody(Json.toJson(request))
      .execute
      .flatMap {
        response =>
          response.status match {
            case NO_CONTENT => logger.info(s"CTC to SDES successful"); Future.successful(())
            case _ =>
              logger.error(s"CTC to SDES error message: ${response.body} - ${response.status}")
              Future.failed(UpstreamErrorResponse(response.body, response.status))
          }
      }
  }

}
