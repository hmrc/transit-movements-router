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

package uk.gov.hmrc.transitmovementsrouter.generators

import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Arbitrary
import org.scalacheck.Gen
import uk.gov.hmrc.objectstore.client.Md5Hash
import uk.gov.hmrc.objectstore.client.ObjectSummaryWithMd5
import uk.gov.hmrc.objectstore.client.Path.Directory
import uk.gov.hmrc.objectstore.client.Path.File
import uk.gov.hmrc.transitmovementsrouter.models.MessageId
import uk.gov.hmrc.transitmovementsrouter.models.MessageType
import uk.gov.hmrc.transitmovementsrouter.models.MovementId

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import scala.math.abs

trait CommonGenerators {

  lazy val genShortUUID: Gen[String] = Gen.long.map {
    l: Long =>
      f"${BigInt(abs(l))}%016x"
  }

  implicit lazy val arbitraryMessageId: Arbitrary[MessageId] = Arbitrary {
    genShortUUID.map(MessageId(_))
  }

  implicit lazy val arbitraryMovementId: Arbitrary[MovementId] = Arbitrary {
    genShortUUID.map(MovementId(_))
  }

  implicit lazy val arbitraryMessageType: Arbitrary[MessageType] =
    Arbitrary(Gen.oneOf(MessageType.values))

  // Restricts the date times to the range of positive long numbers to avoid overflows.
  implicit lazy val arbitraryOffsetDateTime: Arbitrary[OffsetDateTime] =
    Arbitrary {
      for {
        millis <- Gen.chooseNum(0, Long.MaxValue / 1000L)
      } yield OffsetDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneOffset.UTC)
    }

  implicit lazy val arbitraryObjectSummaryWithMd5: Arbitrary[ObjectSummaryWithMd5] = Arbitrary {
    for {
      contentLen <- Gen.long
      md5Hash    <- Gen.alphaNumStr.map(Md5Hash.apply)
      instant = Instant.now()
    } yield ObjectSummaryWithMd5(File(Directory("/common-transit-convention-traders"), "conversationid-date"), contentLen, md5Hash, instant)
  }

}
