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

package uk.gov.hmrc.transitmovementsrouter.models.sdes

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import play.api.libs.json.JsSuccess
import play.api.libs.json.Json

import java.time.Instant

class SdesNotificationItemSpec extends AnyFreeSpec with Matchers with ScalaCheckDrivenPropertyChecks {

  "SuccessfulSubmission" - {

    val instant = Instant.now()
    "when SdesNotificationItem is serialized, return an appropriate JsObject" - {
      () =>
        val actual = SdesNotificationItem.SdesNotificationItem.writes(
          SdesNotificationItem(SdesNotification.FileProcessed, "abc.xml", "123", "md5", "123", instant, None, instant, Seq(SdesProperties("name", "value")))
        )
        val expected = Json.obj(
          "notification"      -> SdesNotification.FileProcessed.toString,
          "filename"          -> "abc.xml",
          "correlationID"     -> "123",
          "checksumAlgorithm" -> "md5",
          "checksum"          -> "123",
          "availableUntil"    -> instant,
          "dateTime"          -> instant,
          "properties"        -> Json.obj("name" -> "name", "value" -> "value")
        )
        actual mustBe expected
    }

    "when an appropriate JsObject is deserialized, return SdesNotificationItem" - {
      () =>
        val actual = SdesNotificationItem.SdesNotificationItem.reads(
          Json.obj(
            "notification"      -> SdesNotification.FileProcessed.toString,
            "filename"          -> "abc.xml",
            "correlationID"     -> "123",
            "checksumAlgorithm" -> "md5",
            "checksum"          -> "123",
            "availableUntil"    -> instant,
            "dateTime"          -> instant,
            "properties"        -> Json.obj("name" -> "name", "value" -> "value")
          )
        )
        val expected =
          SdesNotificationItem(SdesNotification.FileProcessed, "abc.xml", "123", "md5", "123", instant, None, instant, Seq(SdesProperties("name", "value")))
        actual mustBe JsSuccess(expected)
    }

  }

}
