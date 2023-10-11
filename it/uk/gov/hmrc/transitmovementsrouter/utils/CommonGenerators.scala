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

package uk.gov.hmrc.transitmovementsrouter.utils

import org.scalacheck.Arbitrary
import org.scalacheck.Gen
import uk.gov.hmrc.transitmovementsrouter.models._

import scala.math.abs

trait CommonGenerators {

  lazy val genShortUUID: Gen[String] = Gen.long.map {
    l: Long =>
      f"${BigInt(abs(l))}%016x"
  }

  implicit lazy val arbitraryMessageId: Arbitrary[MessageId] = Arbitrary {
    genShortUUID.map(MessageId(_))
  }

  implicit lazy val arbitraryEoriNumber: Arbitrary[EoriNumber] = Arbitrary {
    Gen.alphaNumStr.map(
      alphaNum => if (alphaNum.trim.size == 0) EoriNumber("abc123") else EoriNumber(alphaNum) // guard against the empty string
    )
  }

  implicit lazy val arbitraryMovementId: Arbitrary[MovementId] = Arbitrary {
    genShortUUID.map(MovementId(_))
  }

  implicit lazy val arbitraryMessageType: Arbitrary[MessageType] =
    Arbitrary(Gen.oneOf(MessageType.values))

  def alphaNum(maxLen: Int, minLen: Int = 1) = for {
    len <- Gen.choose(minLen, maxLen)
    str <- Gen.stringOfN(len, Gen.alphaNumChar)
  } yield str

  implicit lazy val arbitraryMovementType: Arbitrary[MovementType] = Arbitrary {
    Gen.oneOf(Seq(MovementType("departure"), MovementType("arrival")))
  }

}
