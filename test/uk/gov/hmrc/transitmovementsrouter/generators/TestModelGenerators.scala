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
import uk.gov.hmrc.objectstore.client.Md5Hash
import uk.gov.hmrc.objectstore.client.ObjectSummaryWithMd5
import uk.gov.hmrc.objectstore.client.Path
import uk.gov.hmrc.transitmovementsrouter.models._
import uk.gov.hmrc.transitmovementsrouter.models.responses.FailureDetails
import uk.gov.hmrc.transitmovementsrouter.models.responses.UploadDetails
import uk.gov.hmrc.transitmovementsrouter.models.responses.UpscanResponse
import uk.gov.hmrc.transitmovementsrouter.models.responses.UpscanResponse.DownloadUrl
import uk.gov.hmrc.transitmovementsrouter.models.responses.UpscanResponse.FileStatus
import uk.gov.hmrc.transitmovementsrouter.models.responses.UpscanResponse.Reference
import uk.gov.hmrc.transitmovementsrouter.models.sdes.SdesNotification
import uk.gov.hmrc.transitmovementsrouter.models.sdes.SdesNotificationItem
import uk.gov.hmrc.transitmovementsrouter.models.sdes.SdesProperties

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

trait TestModelGenerators extends BaseGenerators {

  lazy val genShortUUID: Gen[String] = Gen.long.map {
    l: Long =>
      f"${BigInt(l)}%016x"
  }.toString

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
      fileStatus <- Gen.oneOf(FileStatus.values)
      downloadUrl    = if (isSuccess) Gen.alphaNumStr.sample.map(DownloadUrl(_)) else None
      uploadDetails  = if (isSuccess) arbitraryUploadDetails.arbitrary.sample else None
      failureDetails = if (!isSuccess) arbitraryFailureDetails.arbitrary.sample else None
    } yield UpscanResponse(Reference(reference), fileStatus, downloadUrl, uploadDetails, failureDetails)
  }

  implicit val arbitraryObjectSummaryWithMd5: Arbitrary[ObjectSummaryWithMd5] = Arbitrary {
    for {
      movementId <- arbitraryMovementId.arbitrary
      messageId  <- arbitraryMessageId.arbitrary
      lastModified      = Instant.now()
      formattedDateTime = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(lastModified)
      contentLen <- Gen.chooseNum(100, 500)
      hash       <- Gen.stringOfN(4, Gen.alphaChar).map(Md5Hash)
    } yield ObjectSummaryWithMd5(
      Path.Directory("common-transit-convention-traders").file(s"${movementId.value}-${messageId.value}-$formattedDateTime.xml"),
      contentLen,
      hash,
      lastModified
    )
  }

  implicit val arbitraryObjectStoreResourceLocation: Arbitrary[ObjectStoreResourceLocation] = Arbitrary {
    for {
      movementId <- arbitraryMovementId.arbitrary
      messageId  <- arbitraryMessageId.arbitrary
      lastModified      = Instant.now()
      formattedDateTime = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(lastModified)

    } yield ObjectStoreResourceLocation(
      Path.Directory("common-transit-convention-traders").file(s"${movementId.value}-${messageId.value}-$formattedDateTime.xml").asUri,
      Path.File(s"${movementId.value}-${messageId.value}-$formattedDateTime.xml").asUri
    )
  }

  implicit def arbitrarySdesResponse(): Arbitrary[SdesNotificationItem] = Arbitrary {
    for {
      filename          <- Gen.alphaNumStr
      correlationId     <- Gen.alphaNumStr
      checksum          <- Gen.stringOfN(4, Gen.alphaChar)
      checksumAlgorithm <- Gen.alphaNumStr
      notification = SdesNotification.FileProcessed
      received     = Instant.now()
      properties   = Seq(SdesProperties("x-conversation-id", "123e4567-e89b-12d3-a456-426614174000"))
    } yield SdesNotificationItem(
      notification,
      filename,
      correlationId,
      checksum,
      checksumAlgorithm,
      received,
      None,
      received,
      properties
    )
  }

  implicit def arbitrarySdesFailureResponse(): Arbitrary[SdesNotificationItem] = Arbitrary {
    for {
      filename          <- Gen.alphaNumStr
      correlationId     <- Gen.alphaNumStr
      checksum          <- Gen.stringOfN(4, Gen.alphaChar)
      checksumAlgorithm <- Gen.alphaNumStr
      notification = SdesNotification.FileProcessingFailure
      received     = Instant.now()
      failureReason <- Gen.alphaNumStr
      properties = Seq(SdesProperties("x-conversation-id", "123e4567-e89b-12d3-a456-426614174000"))
    } yield SdesNotificationItem(
      notification,
      filename,
      correlationId,
      checksum,
      checksumAlgorithm,
      received,
      Some(failureReason),
      received,
      properties
    )
  }

}
