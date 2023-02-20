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

import akka.stream.Materializer
import com.google.inject.Inject
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import play.api.Logging
import play.api.libs.json.Json
import play.api.mvc.BaseController
import play.api.test.Helpers.stubControllerComponents
import uk.gov.hmrc.transitmovementsrouter.generators.TestModelGenerators

class UpscanResponseParserSpec @Inject() ()(implicit val materializer: Materializer)
    extends AnyFreeSpec
    with BaseController
    with UpscanResponseParser
    with Logging
    with ScalaFutures
    with Matchers
    with ScalaCheckPropertyChecks
    with TestModelGenerators {

  override val controllerComponents = stubControllerComponents()

  "parseUpscanResponse" - {
    "given a successful response in the callback, returns a defined option with value of SuccessfulSubmission" in forAll(
      arbitraryUpscanResponse(true).arbitrary
    ) {
      successUpscanResponse =>
        val json = Json.toJson(successUpscanResponse)
        parseAndLogUpscanResponse(json) mustBe Some(successUpscanResponse)
        successUpscanResponse.isSuccess mustBe true
    }

    "given a failure response in the callback, returns a None" in forAll(
      arbitraryUpscanResponse(false).arbitrary
    ) {
      failureUpscanResponse =>
        val json = Json.toJson(failureUpscanResponse)
        parseAndLogUpscanResponse(json) mustBe None
        failureUpscanResponse.isSuccess mustBe false
    }

    "given a response in the callback that we cannot deserialize, returns a None" in {
      parseAndLogUpscanResponse(Json.obj("reference" -> "abc")) mustBe None
    }
  }

}
