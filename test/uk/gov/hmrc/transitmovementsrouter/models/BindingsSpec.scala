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

package uk.gov.hmrc.transitmovementsrouter.models

import org.scalacheck.Gen
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks

class BindingsSpec extends AnyFreeSpec with Matchers with ScalaCheckDrivenPropertyChecks {

  val validHexString: Gen[String] = Gen.listOfN(16, Gen.hexChar).map(_.mkString.toLowerCase)

  def genDual(lead: Gen[String], trail: Gen[String]) = s"${lead.sample.get}-${trail.sample.get}"

  val tooShortHexString: Gen[String] = Gen
    .chooseNum(1, 15)
    .flatMap(
      x => Gen.listOfN(x, Gen.hexChar)
    )
    .map(_.mkString.toLowerCase)

  val tooLongHexString: Gen[String] = Gen
    .chooseNum(17, 30)
    .flatMap(
      x => Gen.listOfN(x, Gen.hexChar)
    )
    .map(_.mkString.toLowerCase)

  val shortLead  = genDual(tooShortHexString, validHexString)
  val shortTrail = genDual(validHexString, tooShortHexString)
  val longLead   = genDual(tooLongHexString, validHexString)
  val longTrail  = genDual(validHexString, tooLongHexString)
  val shortLong  = genDual(tooShortHexString, tooLongHexString)
  val longShort  = genDual(tooLongHexString, tooShortHexString)

  val invalidSet = Seq(shortLead, shortTrail, longLead, longTrail, shortLong, longShort)

  val lead  = validHexString.sample.get
  val trail = validHexString.sample.get

  val movementId = "63629b56d8d9c033"
  val messageId  = "73629b4d1d74477e"
  val valid      = "63629b56-d8d9-c033-7362-9b4d1d74477e"

  val testBinding = Bindings.dualBinding

  "dual binding" - {

    "bind" - {
      "check if it obeys 32 character hexadecimal string's split by 4 hyphen's pattern must be accepted" in {
        testBinding.bind("test", valid) mustBe Right((MovementId(movementId), MessageId(messageId)))
      }

      "an invalid dual hex string must not be accepted" in invalidSet.foreach {
        value =>
          testBinding.bind("test", value) mustBe Left(s"test: Value $value is not a pair of 32 character hexadecimal string's split by a hyphen")
      }
    }

    "unbind of the (MovementId, MessageId) tuple" in {
      testBinding.unbind("test", (MovementId(movementId), MessageId(messageId))) mustBe valid
    }

  }

}
