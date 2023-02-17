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

import org.scalatest.concurrent.ScalaFutures
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import play.api.libs.json.Json
import uk.gov.hmrc.transitmovementsrouter.generators.TestModelGenerators

class UpscanServiceSpec extends AnyFreeSpec with ScalaFutures with Matchers with ScalaCheckPropertyChecks with TestModelGenerators {

  val UpscanService = new UpscanServiceImpl

  "parseUpscanResponse" - {
    "given a successful response in the callback, returns a defined option with value of SuccessfulSubmission" in forAll(
      arbitrarySuccessfulSubmission.arbitrary
    ) {
      successfulSubmission =>
        val json = Json.toJson(successfulSubmission)
        UpscanService.parseUpscanResponse(json) mustBe Some(successfulSubmission)
    }

    "given a failure response in the callback, returns a None" in forAll(
      arbitrarySubmissionFailure.arbitrary
    ) {
      submissionFailure =>
        val json = Json.toJson(submissionFailure)
        UpscanService.parseUpscanResponse(json) mustBe None
    }

    "given a response in the callback that we cannot deserialize, returns a None" in {
      UpscanService.parseUpscanResponse(Json.obj("reference" -> "abc")) mustBe None
    }
  }

}
