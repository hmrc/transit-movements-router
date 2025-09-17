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

import cats.data.EitherT
import play.api.Logging
import play.api.libs.json.JsValue
import play.api.mvc.BaseController
import uk.gov.hmrc.transitmovementsrouter.controllers.errors.PresentationError
import uk.gov.hmrc.transitmovementsrouter.models.responses.UpscanFailedResponse
import uk.gov.hmrc.transitmovementsrouter.models.responses.UpscanResponse
import uk.gov.hmrc.transitmovementsrouter.models.responses.UpscanSuccessResponse

import scala.concurrent.Future

trait UpscanResponseParser {
  self: BaseController & Logging =>

  def parseAndLogUpscanResponse(responseBody: JsValue): EitherT[Future, PresentationError, UpscanResponse] =
    EitherT {
      responseBody
        .validate[UpscanResponse]
        .map { upscanResponse =>
          logResponse(Some(upscanResponse))
          Future.successful(Right(upscanResponse))
        }
        .getOrElse {
          logResponse(None)
          Future.successful(Left(PresentationError.badRequestError("Unexpected Upscan callback response")))
        }
    }

  private def logResponse(upscanResponse: Option[UpscanResponse]): Unit =
    upscanResponse match {
      case Some(UpscanSuccessResponse(reference, _, _)) =>
        logger.info(s"Received a successful response from Upscan callback for the following reference: $reference")
      case Some(UpscanFailedResponse(reference, failureDetails)) =>
        logger.warn(
          s"Received a failure response from Upscan callback for the following reference: $reference. Failure reason: ${failureDetails.failureReason}. Failure message: ${failureDetails.message}"
        )
      case _ => logger.error("Unable to parse unexpected response from Upscan")
    }

}
