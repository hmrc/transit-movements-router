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

import play.api.libs.json.Format
import play.api.libs.json.JsString
import play.api.libs.json.Json
import play.api.libs.json.Reads
import play.api.libs.json.Writes
import uk.gov.hmrc.transitmovementsrouter.models.responses.UpscanResponse.DownloadUrl
import uk.gov.hmrc.transitmovementsrouter.models.responses.UpscanResponse.FileStatus
import uk.gov.hmrc.transitmovementsrouter.models.responses.UpscanResponse.Reference

import java.time.Instant

final case class UploadDetails(fileName: String, fileMimeType: String, uploadTimestamp: Instant, checksum: String, size: Long)

object UploadDetails {
  implicit val format = Json.format[UploadDetails]
}

final case class FailureDetails(failureReason: String, message: String)

object FailureDetails {
  implicit val format = Json.format[FailureDetails]
}

object UpscanResponse {

  case class Reference(value: String) extends AnyVal

  object Reference {

    implicit val format: Format[Reference] =
      Format(
        Reads.of[String].map(Reference(_)),
        Writes(
          ref => JsString(ref.value)
        )
      )
  }

  case class DownloadUrl(value: String) extends AnyVal

  object DownloadUrl {

    implicit val format: Format[DownloadUrl] =
      Format(
        Reads.of[String].map(DownloadUrl(_)),
        Writes(
          ref => JsString(ref.value)
        )
      )
  }

  case class FileStatus(value: String) extends AnyVal

  object FileStatus {

    implicit val format: Format[FileStatus] =
      Format(
        Reads.of[String].map(FileStatus(_)),
        Writes(
          ref => JsString(ref.value)
        )
      )
  }

  implicit val upscanResponseFormat = Json.format[UpscanResponse]
}

final case class UpscanResponse(
  reference: Reference,
  fileStatus: FileStatus,
  downloadUrl: Option[DownloadUrl],
  uploadDetails: Option[UploadDetails],
  failureDetails: Option[FailureDetails]
) {
  val isSuccess = uploadDetails.isDefined
}
