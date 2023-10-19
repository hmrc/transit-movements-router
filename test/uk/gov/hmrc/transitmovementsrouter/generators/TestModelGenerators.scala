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
import uk.gov.hmrc.transitmovementsrouter.models.MessageType.responseValues
import uk.gov.hmrc.transitmovementsrouter.models._
import uk.gov.hmrc.transitmovementsrouter.models.responses.FailureDetails
import uk.gov.hmrc.transitmovementsrouter.models.responses.UploadDetails
import uk.gov.hmrc.transitmovementsrouter.models.responses.UpscanFailedResponse
import uk.gov.hmrc.transitmovementsrouter.models.responses.UpscanResponse.DownloadUrl
import uk.gov.hmrc.transitmovementsrouter.models.responses.UpscanResponse.Reference
import uk.gov.hmrc.transitmovementsrouter.models.responses.UpscanSuccessResponse
import uk.gov.hmrc.transitmovementsrouter.models.sdes.SdesNotification
import uk.gov.hmrc.transitmovementsrouter.models.sdes.SdesNotificationType
import uk.gov.hmrc.transitmovementsrouter.models.sdes.SdesProperties
import uk.gov.hmrc.transitmovementsrouter.utils.RouterHeaderNames

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64

trait TestModelGenerators extends BaseGenerators {

  implicit lazy val arbitraryCustomsOffice: Arbitrary[CustomsOffice] =
    Arbitrary {
      for {
        destination <- Gen.oneOf(Seq("GB", "XI"))
        id          <- intWithMaxLength(7, 7)
      } yield CustomsOffice(s"$destination$id")
    }

  implicit lazy val arbitraryEoriNumber: Arbitrary[EoriNumber] =
    Arbitrary {
      for {
        country <- Gen.oneOf("GB", "XI")
        code    <- Gen.stringOfN(10, Gen.numChar)
      } yield EoriNumber(s"$country$code")
    }

  implicit lazy val arbitraryMovementType: Arbitrary[MovementType] =
    Arbitrary {
      Gen.oneOf(MovementType("departures"), MovementType("arrivals"))
    }

  implicit lazy val arbitraryMovementId: Arbitrary[MovementId] =
    Arbitrary {
      Gen.listOfN(16, Gen.hexChar).map(_.mkString.toLowerCase).map(MovementId(_))
    }

  implicit lazy val arbitraryMessageId: Arbitrary[MessageId] =
    Arbitrary {
      Gen.listOfN(16, Gen.hexChar).map(_.mkString.toLowerCase).map(MessageId(_))
    }

  implicit lazy val arbitraryConversationId: Arbitrary[ConversationId] =
    Arbitrary {
      for {
        movementId <- arbitraryMovementId.arbitrary
        messageId  <- arbitraryMessageId.arbitrary
      } yield ConversationId(movementId, messageId)
    }

  implicit lazy val arbitraryMessageType: Arbitrary[MessageType] =
    Arbitrary(Gen.oneOf(MessageType.values))

  implicit lazy val arbitraryResponseMessageType: Arbitrary[ResponseMessageType] =
    Arbitrary(Gen.oneOf(responseValues))

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

  implicit val arbitraryUpscanSuccessResponse: Arbitrary[UpscanSuccessResponse] = Arbitrary {
    for {
      reference     <- Gen.alphaNumStr.map(Reference.apply)
      downloadUrl   <- Gen.alphaNumStr.map(DownloadUrl.apply)
      uploadDetails <- arbitraryUploadDetails.arbitrary
    } yield UpscanSuccessResponse(reference, downloadUrl, uploadDetails)
  }

  implicit val arbitraryUpscanFailedResponse: Arbitrary[UpscanFailedResponse] = Arbitrary {
    for {
      reference      <- Gen.alphaNumStr.map(Reference.apply)
      failureDetails <- arbitraryFailureDetails.arbitrary
    } yield UpscanFailedResponse(reference, failureDetails)
  }

  private def md5hashbase64(string: String): String =
    Base64.getEncoder.encodeToString(MessageDigest.getInstance("MD5").digest(string.getBytes(StandardCharsets.UTF_8)))

  implicit val arbitraryMd5Hash: Arbitrary[Md5Hash] = Arbitrary {
    Gen.alphaNumStr.map(md5hashbase64).map(Md5Hash)
  }

  implicit val arbitraryObjectSummaryWithMd5: Arbitrary[ObjectSummaryWithMd5] = Arbitrary {
    for {
      movementId <- arbitraryMovementId.arbitrary
      messageId  <- arbitraryMessageId.arbitrary
      lastModified      = Instant.now()
      formattedDateTime = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(lastModified)
      contentLen <- Gen.chooseNum(100, 500)
      hash       <- arbitraryMd5Hash.arbitrary
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

  implicit def arbitrarySdesResponse(conversationId: ConversationId): Arbitrary[SdesNotification] = Arbitrary {
    for {
      filename          <- Gen.alphaNumStr
      correlationId     <- Gen.alphaNumStr
      checksum          <- Gen.stringOfN(4, Gen.alphaChar)
      checksumAlgorithm <- Gen.alphaNumStr
      notification      <- Gen.oneOf(SdesNotificationType.values)
      received   = Instant.now()
      properties = Seq(SdesProperties(RouterHeaderNames.CONVERSATION_ID.toLowerCase(), conversationId.value.toString))
    } yield SdesNotification(
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
}
