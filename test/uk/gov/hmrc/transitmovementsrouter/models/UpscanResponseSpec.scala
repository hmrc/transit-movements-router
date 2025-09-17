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

package uk.gov.hmrc.transitmovementsrouter.models

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import play.api.libs.json.JsSuccess
import play.api.libs.json.Json
import uk.gov.hmrc.transitmovementsrouter.models.responses.FailureDetails
import uk.gov.hmrc.transitmovementsrouter.models.responses.UploadDetails
import uk.gov.hmrc.transitmovementsrouter.models.responses.UpscanFailedResponse
import uk.gov.hmrc.transitmovementsrouter.models.responses.UpscanResponse
import uk.gov.hmrc.transitmovementsrouter.models.responses.UpscanResponse.DownloadUrl
import uk.gov.hmrc.transitmovementsrouter.models.responses.UpscanResponse.Reference
import uk.gov.hmrc.transitmovementsrouter.models.responses.UpscanSuccessResponse

import java.time.OffsetDateTime

class UpscanResponseSpec extends AnyFreeSpec with Matchers with ScalaCheckDrivenPropertyChecks {

  "Successful submission response" - {
    "deserializes correctly" in {
      val jsonSuccessResponse = Json.obj(
        "reference"     -> "11370e18-6e24-453e-b45a-76d3e32ea33d",
        "downloadUrl"   -> "https://bucketName.s3.eu-west-2.amazonaws.com?1235676",
        "fileStatus"    -> "READY",
        "uploadDetails" -> Json.obj(
          "fileName"        -> "test.pdf",
          "fileMimeType"    -> "application/pdf",
          "uploadTimestamp" -> "2018-04-24T09:30:00Z",
          "checksum"        -> "396f101dd52e8b2ace0dcf5ed09b1d1f030e608938510ce46e7a5c7a4e775100",
          "size"            -> 987
        )
      )

      jsonSuccessResponse.validate[UpscanResponse] match {
        case JsSuccess(_: UpscanSuccessResponse, _) => succeed
        case _                                      => fail("Expected to be a success response from upscan")
      }
    }

    "serializes correctly" in {
      val response = UpscanSuccessResponse(
        Reference("11370e18-6e24-453e-b45a-76d3e32ea33d"),
        DownloadUrl("https://bucketName.s3.eu-west-2.amazonaws.com?1235676"),
        UploadDetails(
          "test.pdf",
          "application/pdf",
          OffsetDateTime.parse("2018-04-24T09:30:00Z").toInstant,
          "396f101dd52e8b2ace0dcf5ed09b1d1f030e608938510ce46e7a5c7a4e775100",
          987
        )
      )

      Json.toJson[UpscanResponse](response) mustBe Json.obj(
        "reference"     -> "11370e18-6e24-453e-b45a-76d3e32ea33d",
        "downloadUrl"   -> "https://bucketName.s3.eu-west-2.amazonaws.com?1235676",
        "fileStatus"    -> "READY",
        "uploadDetails" -> Json.obj(
          "fileName"        -> "test.pdf",
          "fileMimeType"    -> "application/pdf",
          "uploadTimestamp" -> "2018-04-24T09:30:00Z",
          "checksum"        -> "396f101dd52e8b2ace0dcf5ed09b1d1f030e608938510ce46e7a5c7a4e775100",
          "size"            -> 987
        )
      )
    }
  }

  "Failed submission response" - {
    val jsonFailureResponse = Json.obj(
      "reference"      -> "11370e18-6e24-453e-b45a-76d3e32ea33d",
      "fileStatus"     -> "FAILED",
      "failureDetails" -> Json.obj(
        "failureReason" -> "QUARANTINE",
        "message"       -> "This file has a virus"
      )
    )
    "deserializes correctly" in {
      jsonFailureResponse.validate[UpscanResponse] match {
        case JsSuccess(_: UpscanFailedResponse, _) => succeed
        case _                                     => fail("Expected to be a failure response from upscan")
      }
    }

    "serializes correctly" in {
      val upscanFailureResponse = UpscanFailedResponse(
        Reference("11370e18-6e24-453e-b45a-76d3e32ea33d"),
        FailureDetails(
          "QUARANTINE",
          "This file has a virus"
        )
      )

      Json.toJson[UpscanResponse](upscanFailureResponse) mustBe jsonFailureResponse
    }
  }

}
