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

package uk.gov.hmrc.transitmovementsrouter.models.responses

import play.api.libs.json.Json

import java.time.Instant

sealed trait UpscanResponse

final case class UploadDetails(fileName: String, fileMimeType: String, uploadTimestamp: Instant, checksum: String, size: Long)

object UploadDetails {
  implicit val format = Json.format[UploadDetails]
}

final case class FailureDetails(failureReason: String, message: String)

object FailureDetails {
  implicit val format = Json.format[FailureDetails]
}

object UpscanResponse {

  final case class SuccessfulSubmission(reference: String, downloadUrl: String, fileStatus: String, uploadDetails: UploadDetails) extends UpscanResponse

  final case class SubmissionFailure(reference: String, fileStatus: String, failureDetails: FailureDetails) extends UpscanResponse

  implicit val successFormat = Json.format[SuccessfulSubmission]

  implicit val failureFormat = Json.format[SubmissionFailure]
}
