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

package uk.gov.hmrc.transitmovementsrouter.models.formats

import cats.data.NonEmptyList
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import play.api.libs.json.JsArray
import play.api.libs.json.JsNumber
import play.api.libs.json.JsSuccess
import play.api.libs.json.JsValue

class CommonFormatsSpec extends AnyFreeSpec with Matchers {

  val nonEmptyListJson: JsValue = JsArray(
    Seq(1, 2, 3, 4).map(n => JsNumber(n))
  )

  "Reads converts a json array into a nonEmptyList" in {
    CommonFormats.nonEmptyListFormat[Int].reads(nonEmptyListJson) mustBe JsSuccess(NonEmptyList[Int](1, List(2, 3, 4)))
  }

  "Writes converts a nonEmptyList into a json array" in {
    CommonFormats.nonEmptyListFormat[Int].writes(NonEmptyList[Int](1, List(2, 3, 4))) mustBe nonEmptyListJson
  }

}
