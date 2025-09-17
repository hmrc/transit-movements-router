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

import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Gen
import org.scalatest.OptionValues
import org.scalatest.concurrent.PatienceConfiguration.Timeout
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import uk.gov.hmrc.transitmovementsrouter.base.StreamTestHelpers.createStream
import uk.gov.hmrc.transitmovementsrouter.base._
import uk.gov.hmrc.transitmovementsrouter.generators.TestModelGenerators
import uk.gov.hmrc.transitmovementsrouter.models._
import uk.gov.hmrc.transitmovementsrouter.models.errors.CustomOfficeExtractorError
import uk.gov.hmrc.transitmovementsrouter.models.errors.CustomOfficeExtractorError.NoElementFound

import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import scala.concurrent.duration.DurationInt

class CustomOfficeExtractorServiceSpec
    extends AnyFreeSpec
    with ScalaFutures
    with TestActorSystem
    with Matchers
    with MockitoSugar
    with ScalaCheckDrivenPropertyChecks
    with TestModelGenerators
    with OptionValues {

  val serviceUnderTest = new CustomOfficeExtractorServiceImpl()

  "Extract customs office" - {

    MessageType.departureRequestValues.foreach { messageType =>
      s"${messageType.code} should generate a valid departure office for a given GB payload" in forAll(
        messageWithDepartureOfficeNode(messageType, "GB")
      ) { officeOfDepartureXML =>
        val payload  = createStream(officeOfDepartureXML._1)
        val response = serviceUnderTest.extractCustomOffice(
          payload,
          messageType
        )

        whenReady(response.value, Timeout(2.seconds)) { r =>
          r.mustBe(Right(CustomsOffice(officeOfDepartureXML._2)))

        }
      }

      s"${messageType.code} should generate a valid departure office for a given Xi payload" in forAll(
        messageWithDepartureOfficeNode(messageType, "XI")
      ) { officeOfDepartureXML =>
        val payload  = createStream(officeOfDepartureXML._1)
        val response = serviceUnderTest.extractCustomOffice(
          payload,
          messageType
        )

        whenReady(response.value, Timeout(2.seconds)) { r =>
          r.mustBe(Right(CustomsOffice(officeOfDepartureXML._2)))

        }
      }

      s"${messageType.code} should generate an invalid office of destination for a non GB/XI payload" in forAll(
        messageWithDepartureOfficeNode(messageType, "FR")
      ) { officeOfDeparture =>
        val referenceNumber = officeOfDeparture._2
        val payload         = createStream(officeOfDeparture._1)
        val response        = serviceUnderTest.extractCustomOffice(
          payload,
          messageType
        )

        whenReady(response.value, Timeout(2.seconds)) { r =>
          r.mustBe(
            Left(
              CustomOfficeExtractorError.UnrecognisedOffice(
                s"Did not recognise office: $referenceNumber",
                CustomsOffice(referenceNumber),
                messageType.officeNode
              )
            )
          )

        }
      }

      s"${messageType.code} returns NoElementFound(referenceNumber) when it does not find an office of departure element" in {
        val payload  = createStream(emptyMessage(messageType.rootNode))
        val response = serviceUnderTest.extractCustomOffice(
          payload,
          messageType
        )

        whenReady(response.value, Timeout(2.seconds)) { r =>
          r.mustBe(Left(NoElementFound("referenceNumber")))
        }
      }
    }

    MessageType.arrivalRequestValues.foreach { messageType =>
      s"${messageType.code} should generate a valid office of destination for a given GB payload" in forAll(
        messageWithDestinationOfficeNode(messageType, "GB")
      ) { officeOfDestinationXML =>
        val payload  = createStream(officeOfDestinationXML._1)
        val response = serviceUnderTest.extractCustomOffice(
          payload,
          messageType
        )

        whenReady(response.value, Timeout(2.seconds)) { r =>
          r.mustBe(Right(CustomsOffice(officeOfDestinationXML._2)))
        }
      }

      s"${messageType.code} should generate a valid office of destination for a given XI payload" in forAll(
        messageWithDestinationOfficeNode(messageType, "XI")
      ) { officeOfDestinationXML =>
        val payload  = createStream(officeOfDestinationXML._1)
        val response = serviceUnderTest.extractCustomOffice(
          payload,
          messageType
        )

        whenReady(response.value, Timeout(2.seconds)) { r =>
          r.mustBe(Right(CustomsOffice(officeOfDestinationXML._2)))

        }
      }

      s"${messageType.code} should generate an invalid office of destination for a non GB/XI payload" in forAll(
        messageWithDestinationOfficeNode(messageType, "FR")
      ) { officeOfDestinationXML =>
        val payload  = createStream(officeOfDestinationXML._1)
        val response = serviceUnderTest.extractCustomOffice(
          payload,
          messageType
        )

        whenReady(response.value, Timeout(2.seconds)) { r =>
          r.mustBe(
            Left(
              CustomOfficeExtractorError.UnrecognisedOffice(
                s"Did not recognise office: ${officeOfDestinationXML._2}",
                CustomsOffice(officeOfDestinationXML._2),
                messageType.officeNode
              )
            )
          )
        }
      }

    }
  }

  def messageWithDepartureOfficeNode(messageType: RequestMessageType, referenceType: String): Gen[(String, String)] =
    for {
      dateTime        <- arbitrary[OffsetDateTime].map(_.toLocalDateTime.truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_LOCAL_DATE))
      referenceNumber <- createReferenceNumberWithPrefix(referenceType)
    } yield (
      s"<${messageType.rootNode}><preparationDateAndTime>$dateTime</preparationDateAndTime><${messageType.officeNode}><referenceNumber>$referenceNumber</referenceNumber></${messageType.officeNode}></${messageType.rootNode}>",
      referenceNumber
    )

  def messageWithDestinationOfficeNode(messageType: RequestMessageType, referenceType: String): Gen[(String, String)] =
    for {
      referenceNumber <- createReferenceNumberWithPrefix(referenceType)
    } yield (
      s"""<${messageType.rootNode}>
           |<messageType>${messageType.code}</messageType>
           |<${messageType.officeNode}>
           |  <referenceNumber>$referenceNumber</referenceNumber>
           |</${messageType.officeNode}>
           |</${messageType.rootNode}>""".stripMargin,
      referenceNumber
    )

  def emptyMessage(messageTypeNode: String): String = s"<$messageTypeNode/>"

  def createReferenceNumberWithPrefix(prefix: String): Gen[String] = intWithMaxLength(7, 7).map(n => s"$prefix$n")
}
