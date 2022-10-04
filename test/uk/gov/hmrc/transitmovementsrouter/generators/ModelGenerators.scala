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

package uk.gov.hmrc.transitmovementsrouter.generators

import org.scalacheck.Arbitrary
import org.scalacheck.Gen
import uk.gov.hmrc.transitmovementsrouter.models.CustomsOfficeOfDestinationActual
import uk.gov.hmrc.transitmovementsrouter.models.MessageId
import uk.gov.hmrc.transitmovementsrouter.models.MessageSender
import uk.gov.hmrc.transitmovementsrouter.models.MessageType
import uk.gov.hmrc.transitmovementsrouter.models.MovementId
import uk.gov.hmrc.transitmovementsrouter.models.OfficeOfDeparture

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

trait ModelGenerators extends BaseGenerators {

  implicit lazy val arbitraryOfficeOfDeparture: Arbitrary[OfficeOfDeparture] =
    Arbitrary {
      for {
        destination <- Gen.oneOf(Seq("GB", "XI"))
        id          <- intWithMaxLength(7, 7)
      } yield OfficeOfDeparture(s"$destination$id")
    }

  // generic?
  implicit lazy val arbitraryCustomsOfficeOfDestinationActual: Arbitrary[CustomsOfficeOfDestinationActual] =
    Arbitrary {
      for {
        destination <- Gen.oneOf(Seq("GB", "XI"))
        id          <- intWithMaxLength(7, 7)
      } yield CustomsOfficeOfDestinationActual(s"$destination$id")
    }

  implicit lazy val arbitraryMessageSender: Arbitrary[MessageSender] =
    Arbitrary {
      for {
        movementId <- alphaNumeric(12)
        messageId  <- alphaNumeric(12)
      } yield MessageSender(s"$movementId-$messageId")
    }

  implicit lazy val arbitraryMovementId: Arbitrary[MovementId] =
    Arbitrary {
      Gen.listOfN(16, Gen.hexChar).map(_.mkString).map(MovementId)
    }

  implicit lazy val arbitraryMessageId: Arbitrary[MessageId] =
    Arbitrary {
      Gen.listOfN(16, Gen.hexChar).map(_.mkString).map(MessageId)
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

}
