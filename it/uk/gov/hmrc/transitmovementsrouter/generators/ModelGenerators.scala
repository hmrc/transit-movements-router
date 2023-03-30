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
import uk.gov.hmrc.transitmovementsrouter.models.ConversationId
import uk.gov.hmrc.transitmovementsrouter.models.CustomsOffice
import uk.gov.hmrc.transitmovementsrouter.models.MessageId
import uk.gov.hmrc.transitmovementsrouter.models.MessageType
import uk.gov.hmrc.transitmovementsrouter.models.MovementId
import uk.gov.hmrc.transitmovementsrouter.models.sdes.SdesAudit
import uk.gov.hmrc.transitmovementsrouter.models.sdes.SdesChecksum
import uk.gov.hmrc.transitmovementsrouter.models.sdes.SdesFile
import uk.gov.hmrc.transitmovementsrouter.models.sdes.SdesFilereadyRequest
import uk.gov.hmrc.transitmovementsrouter.models.sdes.SdesProperties

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

trait ModelGenerators extends BaseGenerators {

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

  implicit def arbUUID: Arbitrary[UUID] = Arbitrary {
    UUID.randomUUID()
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

  implicit val arbitrarySdesFilereadyRequest: Arbitrary[SdesFilereadyRequest] = Arbitrary {
    for {
      informationType <- Gen.alphaStr
      srn             <- Gen.alphaStr
      movementId      <- arbitraryMovementId.arbitrary
      messageId       <- arbitraryMessageId.arbitrary
      objectSummary   <- arbitraryObjectSummaryWithMd5.arbitrary
      uuid            <- arbUUID.arbitrary

    } yield SdesFilereadyRequest(
      informationType,
      SdesFile(
        srn,
        objectSummary.location.fileName,
        objectSummary.location.asUri,
        SdesChecksum(value = objectSummary.contentMd5.value),
        objectSummary.contentLength,
        Seq(SdesProperties("x-conversation-id", ConversationId(movementId, messageId).value.toString))
      ),
      SdesAudit(uuid.toString)
    )
  }

}
