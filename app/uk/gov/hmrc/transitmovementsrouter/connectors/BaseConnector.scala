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

import play.api.http.HeaderNames
import uk.gov.hmrc.http.HttpResponse
import uk.gov.hmrc.http.UpstreamErrorResponse
import uk.gov.hmrc.http.client.RequestBuilder
import uk.gov.hmrc.transitmovementsrouter.config.AppConfig
import uk.gov.hmrc.transitmovementsrouter.models.EoriNumber
import uk.gov.hmrc.transitmovementsrouter.models.MessageId
import uk.gov.hmrc.transitmovementsrouter.models.MessageType
import uk.gov.hmrc.transitmovementsrouter.models.MovementId
import uk.gov.hmrc.transitmovementsrouter.models.MovementType

import scala.concurrent.Future

trait BaseConnector {

  implicit class RequestBuilderHelpers(requestBuilder: RequestBuilder) {

    def withInternalAuthToken(implicit appConfig: AppConfig): RequestBuilder =
      requestBuilder.setHeader(HeaderNames.AUTHORIZATION -> appConfig.internalAuthToken)

    def withMessageType(messageType: Option[MessageType]): RequestBuilder =
      messageType
        .map(
          t => requestBuilder.setHeader("X-Message-Type" -> t.code)
        )
        .getOrElse(requestBuilder)

    def withMovementId(movementId: Option[MovementId]): RequestBuilder =
      if (movementId.isDefined)
        requestBuilder.setHeader("X-Audit-Meta-Movement-Id" -> movementId.get.value)
      else requestBuilder

    def withEoriNumber(eoriNumber: Option[EoriNumber]): RequestBuilder =
      if (eoriNumber.isDefined)
        requestBuilder.setHeader("X-Audit-Meta-EORI" -> eoriNumber.get.value)
      else requestBuilder

    def withMovementType(movementType: Option[MovementType]): RequestBuilder =
      if (movementType.isDefined)
        requestBuilder.setHeader("X-Audit-Meta-Movement-Type" -> movementType.get.value)
      else requestBuilder

    def withAuditMessageType(messageType: Option[MessageType]): RequestBuilder =
      if (messageType.isDefined)
        requestBuilder.setHeader("X-Audit-Meta-Message-Type" -> messageType.get.code)
      else requestBuilder

    def withMessageId(messageId: Option[MessageId]): RequestBuilder =
      if (messageId.isDefined)
        requestBuilder.setHeader("X-Audit-Meta-Message-Id" -> messageId.get.value)
      else requestBuilder

  }

  implicit class HttpResponseHelpers(response: HttpResponse) {

    def error[A]: Future[A] =
      Future.failed(UpstreamErrorResponse(response.body, response.status))
  }

}
