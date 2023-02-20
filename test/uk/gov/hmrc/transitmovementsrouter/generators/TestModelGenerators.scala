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

import org.scalacheck.Arbitrary
import org.scalacheck.Gen
import uk.gov.hmrc.transitmovementsrouter.models.CustomsOffice
import uk.gov.hmrc.transitmovementsrouter.models.MessageId
import uk.gov.hmrc.transitmovementsrouter.models.MessageType
import uk.gov.hmrc.transitmovementsrouter.models.MovementId
import uk.gov.hmrc.transitmovementsrouter.models.RequestMessageType
import uk.gov.hmrc.transitmovementsrouter.models.responses.FailureDetails
import uk.gov.hmrc.transitmovementsrouter.models.responses.UploadDetails
import uk.gov.hmrc.transitmovementsrouter.models.responses.UpscanResponse

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

trait TestModelGenerators extends BaseGenerators {

  implicit lazy val arbitraryCustomsOffice: Arbitrary[CustomsOffice] =
    Arbitrary {
      for {
        destination <- Gen.oneOf(Seq("GB", "XI"))
        id          <- intWithMaxLength(7, 7)
      } yield CustomsOffice(s"$destination$id")
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

  implicit lazy val arbitraryRequestMessageType: Arbitrary[RequestMessageType] =
    Arbitrary(Gen.oneOf(MessageType.requestValues))

  // Restricts the date times to the range of positive long numbers to avoid overflows.
  implicit lazy val arbitraryOffsetDateTime: Arbitrary[OffsetDateTime] =
    Arbitrary {
      for {
        millis <- Gen.chooseNum(0, Long.MaxValue / 1000L)
      } yield OffsetDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneOffset.UTC)
    }

  implicit lazy val arbitraryUploadDetails: Arbitrary[UploadDetails] = Arbitrary {
    for {
      fileName     <- Gen.alphaNumStr
      fileMimeType <- Gen.alphaNumStr
      checksum     <- Gen.alphaNumStr
      size         <- Gen.long
    } yield UploadDetails(fileName, fileMimeType, Instant.now(), checksum, size)
  }

  implicit lazy val arbitraryFailureDetails: Arbitrary[FailureDetails] = Arbitrary {
    for {
      failureReason <- Gen.alphaNumStr
      message       <- Gen.alphaNumStr
    } yield FailureDetails(failureReason, message)
  }

  implicit def arbitraryUpscanResponse(isSuccess: Boolean): Arbitrary[UpscanResponse] = Arbitrary {
    for {
      reference  <- Gen.alphaNumStr
      fileStatus <- Gen.alphaNumStr
      uploadDetails  = if (isSuccess) arbitraryUploadDetails.arbitrary.sample else None
      failureDetails = if (!isSuccess) arbitraryFailureDetails.arbitrary.sample else None
    } yield UpscanResponse(reference, fileStatus, uploadDetails, failureDetails)
  }

}
