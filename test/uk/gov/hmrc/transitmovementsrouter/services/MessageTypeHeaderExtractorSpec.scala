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

import org.scalacheck.Gen
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import play.api.mvc.Headers
import uk.gov.hmrc.transitmovementsrouter.models.MessageType
import uk.gov.hmrc.transitmovementsrouter.models.errors.HeaderExtractError.InvalidMessageType
import uk.gov.hmrc.transitmovementsrouter.models.errors.HeaderExtractError.NoHeaderFound

class MessageTypeHeaderExtractorSpec extends AnyFreeSpec with ScalaFutures with Matchers with ScalaCheckPropertyChecks {

  val messageTypeGenerator = Gen.oneOf(MessageType.values.toSeq)

  "extract" - {
    "returns Left(NoHeaderFound) when no X-Message-Type header" in {
      whenReady(MessageTypeHeaderExtractor.extract(Headers()).value) {
        res => res mustBe a[Left[NoHeaderFound, _]]
      }
    }

    "returns Left(InvalidMessageType) when X-Message-Type header value is nonsense" in {
      whenReady(MessageTypeHeaderExtractor.extract(Headers("X-Message-Type" -> "abcde")).value) {
        res => res mustBe a[Left[InvalidMessageType, _]]
      }
    }

    "returns Right(MessageType)" in forAll(messageTypeGenerator) {
      mt: MessageType =>
        whenReady(MessageTypeHeaderExtractor.extract(Headers("X-Message-Type" -> mt.code)).value) {
          res => res mustBe a[Right[_, _]]
        }
    }
  }
}
