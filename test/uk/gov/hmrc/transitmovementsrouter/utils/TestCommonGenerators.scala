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
import uk.gov.hmrc.transitmovementsrouter.models.AuditType
import uk.gov.hmrc.transitmovementsrouter.models.EoriNumber
import uk.gov.hmrc.transitmovementsrouter.models.MessageId
import uk.gov.hmrc.transitmovementsrouter.models.MessageType
import uk.gov.hmrc.transitmovementsrouter.models.MovementId
import uk.gov.hmrc.transitmovementsrouter.models.MovementType

trait TestCommonGenerators {

  lazy val genShortUUID: Gen[String] = Gen.long.map {
    l: Long =>
      f"${BigInt(l)}%016x"
  }

  implicit lazy val arbitraryMessageId: Arbitrary[MessageId] = Arbitrary {
    genShortUUID.map(MessageId(_))
  }

  implicit lazy val arbitraryEORINumber: Arbitrary[EoriNumber] = Arbitrary {
    Gen.alphaNumStr.map(EoriNumber(_))
  }

  implicit lazy val arbitraryMessageType: Arbitrary[MessageType] = Arbitrary {
    Gen.oneOf(MessageType.values)
  }

  implicit lazy val arbitraryAuditType: Arbitrary[AuditType] = Arbitrary {
    Gen.oneOf(AuditType.values)
  }

  implicit lazy val arbitraryMovementId: Arbitrary[MovementId] = Arbitrary {
    genShortUUID.map(MovementId(_))
  }

  implicit lazy val arbitraryMovementType: Arbitrary[MovementType] = Arbitrary {
    Gen.oneOf(Seq(MovementType("departure"), MovementType("arrival")))
  }

}
