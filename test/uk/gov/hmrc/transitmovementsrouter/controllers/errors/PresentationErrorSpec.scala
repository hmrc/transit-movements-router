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

package uk.gov.hmrc.transitmovementsrouter.controllers.errors

import org.scalacheck.Gen
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import play.api.libs.json.Json
import uk.gov.hmrc.http.UpstreamErrorResponse
import uk.gov.hmrc.transitmovementsrouter.models.CustomsOffice
import uk.gov.hmrc.transitmovementsrouter.models.LocalReferenceNumber

class PresentationErrorSpec extends AnyFreeSpec with Matchers with MockitoSugar with ScalaCheckDrivenPropertyChecks {

  "Test Json is as expected" - {
    def testStandard(function: String => PresentationError, message: String, code: String) = {
      val sut    = function(message)
      val result = Json.toJson(sut)

      result mustBe Json.obj("message" -> message, "code" -> code)
    }

    "for Forbidden" in testStandard(PresentationError.forbiddenError, "forbidden", "FORBIDDEN")

    "for BadRequest" in testStandard(PresentationError.badRequestError, "bad request", "BAD_REQUEST")

    "for UnsupportedMediaType" in testStandard(PresentationError.unsupportedMediaTypeError, "Unsupported Media Type", "UNSUPPORTED_MEDIA_TYPE")

    "for NotFound" in testStandard(PresentationError.notFoundError, "not found", "NOT_FOUND")

    "for NotImplemented" in testStandard(PresentationError.notImplemented, "Not Implemented", "NOT_IMPLEMENTED")

    "for InvalidOffice" in forAll(Gen.alphaNumStr, Gen.alphaStr) {
      (office, field) =>
        val error  = PresentationError.invalidOfficeError("Invalid Office", CustomsOffice(office), field)
        val result = Json.toJson(error)

        result mustBe Json.obj("message" -> "Invalid Office", "office" -> office, "field" -> field, "code" -> "INVALID_OFFICE")
    }

    Seq(Some(new IllegalStateException("message")), None).foreach {
      exception =>
        val textFragment = exception
          .map(
            _ => "contains"
          )
          .getOrElse("does not contain")
        s"for an unexpected error that $textFragment a Throwable" in {
          // Given this exception
          val exception = new IllegalStateException("message")

          // when we create an error for this
          val sut = InternalServiceError.causedBy(exception)

          // and when we turn it to Json
          val json = Json.toJson(sut)

          // then we should get an expected output
          json mustBe Json.obj("code" -> "INTERNAL_SERVER_ERROR", "message" -> "Internal server error")
        }
    }

    "for an upstream error" in {
      // Given this upstream error
      val upstreamErrorResponse = UpstreamErrorResponse("error", 500)

      // when we create an error for this
      val sut = UpstreamServiceError.causedBy(upstreamErrorResponse)

      // and when we turn it to Json
      val json = Json.toJson(sut)

      // then we should get an expected output
      json mustBe Json.obj("code" -> "INTERNAL_SERVER_ERROR", "message" -> "Internal server error")
    }

    "for duplicate lrn error" in {
      val error  = PresentationError.duplicateLRNError("LRN 1234 was previously used", LocalReferenceNumber("1234"))
      val result = Json.toJson(error)

      result mustBe Json.obj("message" -> "LRN 1234 was previously used", "lrn" -> "1234", "code" -> "CONFLICT")
    }
  }

}
