/*
 * Copyright 2022 HM Revenue & Customs
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

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.mockito.MockitoSugar
import play.api.libs.json._
import uk.gov.hmrc.transitmovementsrouter.models.errors.ErrorCode

class ErrorCodeSpec extends AnyFreeSpec with Matchers with MockitoSugar {

  val errorCodeReads: Reads[ErrorCode] =
    (__ \ "code").read[ErrorCode]

  "ErrorCode Reads correctly maps valid json string to error code" in {

    val validErrorCodeJson: JsValue = Json.parse("""
  {
    "code" : "BAD_REQUEST"
  }
  """)

    errorCodeReads.reads(validErrorCodeJson).get.code mustBe "BAD_REQUEST"
    errorCodeReads.reads(validErrorCodeJson).get.statusCode mustBe 400
  }

  "ErrorCode Reads errors when given invalid json" in {

    val invalidErrorCodeJson: JsValue = Json.parse("""
  {
    "invalid" : "val"
  }
  """)

    errorCodeReads.reads(invalidErrorCodeJson) mustBe a[JsError]

  }

}
