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

package uk.gov.hmrc.transitmovementsrouter.controllers

import play.api.Logging
import play.api.libs.json.JsValue
import play.api.mvc.BaseController
import uk.gov.hmrc.transitmovementsrouter.controllers.errors.PresentationError
import uk.gov.hmrc.transitmovementsrouter.models.sdes.SdesNotification

trait SdesResponseParser {
  self: BaseController with Logging =>

  def parseAndLogSdesResponse(responseBody: JsValue): Either[PresentationError, SdesNotification] =
    responseBody
      .validate[SdesNotification]
      .filter(_.conversationId.isDefined)
      .map(evaluate)
      .getOrElse {
        logger.error("Unable to parse unexpected response from SDES")
        Left(PresentationError.badRequestError("Unexpected SDES callback response"))
      }

  private def evaluate(sdesResponse: SdesNotification) =
    sdesResponse match {
      case SdesNotification(notification, _, _, _, _, _, None, _, _) =>
        logger.info(
          s"Received a successful response from SDES callback for the following x-conversation-id: ${sdesResponse.conversationId.get.value}. Notification Type: $notification"
        )

        Right(sdesResponse)
      case SdesNotification(notification, _, _, _, _, _, Some(failureReason), _, _) =>
        logger.warn(
          s"Received a failure response from SDES callback for the following x-conversation-id: ${sdesResponse.conversationId.get.value}. Notification Type: $notification. Failure reason: $failureReason."
        )
        Right(sdesResponse)
      case _ =>
        logger.error("Unable to parse unexpected response from SDES")
        Left(PresentationError.badRequestError("Unexpected SDES callback response"))
    }
}
