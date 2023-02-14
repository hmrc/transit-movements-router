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

import com.google.inject.ImplementedBy
import play.api.Logging
import play.api.libs.json.JsSuccess
import play.api.libs.json.JsValue
import uk.gov.hmrc.transitmovementsrouter.models.responses.UpscanResponse.SubmissionFailure
import uk.gov.hmrc.transitmovementsrouter.models.responses.UpscanResponse.SuccessfulSubmission

@ImplementedBy(classOf[UpscanServiceImpl])
trait UpscanService {
  def parseUpscanResponse(responseBody: JsValue): Option[SuccessfulSubmission]
}

class UpscanServiceImpl extends UpscanService with Logging {

  def parseUpscanResponse(responseBody: JsValue): Option[SuccessfulSubmission] =
    responseBody
      .validate[SuccessfulSubmission]
      .map {
        successfulSubmission =>
          logger.info(s"Received a successful response from Upscan callback for the following reference: ${successfulSubmission.reference}")
          Some(successfulSubmission)
      }
      .getOrElse {
        responseBody.validate[SubmissionFailure] match {
          case JsSuccess(failure, _) =>
            logger.warn(
              s"Received a failure response from Upscan callback for the following reference: ${failure.reference}. Failure reason: ${failure.failureDetails.failureReason}. Failure message: ${failure.failureDetails.message}"
            )
            None
          case _ =>
            logger.error("Unexpected response format from Upscan")
            None
        }
      }

}
