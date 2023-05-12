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

import org.scalacheck.Arbitrary.arbitrary
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import play.api.Logging
import play.api.libs.json.Json
import play.api.mvc.BaseController
import play.api.mvc.ControllerComponents
import play.api.test.Helpers.stubControllerComponents
import uk.gov.hmrc.transitmovementsrouter.controllers.errors.PresentationError
import uk.gov.hmrc.transitmovementsrouter.generators.TestModelGenerators
import uk.gov.hmrc.transitmovementsrouter.models.responses.UpscanFailedResponse
import uk.gov.hmrc.transitmovementsrouter.models.responses.UpscanResponse
import uk.gov.hmrc.transitmovementsrouter.models.responses.UpscanSuccessResponse

class UpscanResponseParserSpec extends AnyFreeSpec with ScalaFutures with Matchers with ScalaCheckPropertyChecks with TestModelGenerators {

  class TestUpscanResponseParserController extends BaseController with UpscanResponseParser with Logging {
    override protected def controllerComponents: ControllerComponents = stubControllerComponents()
  }

  val testController = new TestUpscanResponseParserController()

  "parseUpscanResponse" - {
    "given a successful response in the callback, returns a defined option with value of UploadDetails" in forAll(
      arbitrary[UpscanSuccessResponse]
    ) {
      successUpscanResponse =>
        val json = Json.toJson[UpscanResponse](successUpscanResponse)
        whenReady(testController.parseAndLogUpscanResponse(json).value) {
          either =>
            either mustBe Right(successUpscanResponse)
        }
    }

    "given a failure response in the callback, returns a defined option with value of FailedDetails" in forAll(
      arbitrary[UpscanFailedResponse]
    ) {
      failureUpscanResponse =>
        val json = Json.toJson[UpscanResponse](failureUpscanResponse)
        whenReady(testController.parseAndLogUpscanResponse(json).value) {
          either =>
            either mustBe Right(failureUpscanResponse)
        }
    }

    "given a response in the callback that we cannot deserialize, returns a PresentationError" in {
      whenReady(testController.parseAndLogUpscanResponse(Json.obj("reference" -> "abc")).value) {
        either =>
          either mustBe Left(PresentationError.badRequestError("Unexpected Upscan callback response"))
      }
    }
  }

}
