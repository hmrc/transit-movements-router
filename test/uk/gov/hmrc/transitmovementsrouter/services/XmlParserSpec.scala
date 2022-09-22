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

package uk.gov.hmrc.transitmovementsrouter.services

import akka.stream.alpakka.xml.scaladsl._
import akka.stream.scaladsl.Sink
import akka.util.ByteString
import org.scalatest.concurrent.ScalaFutures.whenReady
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper
import uk.gov.hmrc.transitmovementsrouter.base.StreamTestHelpers._
import uk.gov.hmrc.transitmovementsrouter.base.TestActorSystem
import uk.gov.hmrc.transitmovementsrouter.generators.ModelGenerators
import uk.gov.hmrc.transitmovementsrouter.models._
import uk.gov.hmrc.transitmovementsrouter.services.error.RoutingError.NoElementFound

import java.time.format.DateTimeFormatter
import scala.xml._

class XmlParserSpec extends AnyFreeSpec with TestActorSystem with Matchers with ModelGenerators {

  "MessageSender parser" - new Setup {
    "when provided with a valid message it extracts the message sender" in {
      val stream       = createParsingEventStream(messageWithMessageSender(MessageType.DeclarationData.rootNode))
      val parsedResult = stream.via(XmlParser.messageSenderExtractor).runWith(Sink.head)

      whenReady(parsedResult) {
        _.right.get mustBe messageSender
      }
    }

    "when provided with a missing messageSender node it returns NoElementFound" in {
      val stream       = createParsingEventStream(messageWithoutMessageSender(MessageType.DeclarationData.rootNode))
      val parsedResult = stream.via(XmlParser.messageSenderExtractor).runWith(Sink.head)

      whenReady(parsedResult) {
        _.mustBe(Left(NoElementFound("messageSender")))
      }
    }

    "when provided with a no value for messageSender node it returns NoElementFound" in {
      val stream       = createParsingEventStream(cc015cWithoutMessageSenderValue)
      val parsedResult = stream.via(XmlParser.messageSenderExtractor).runWith(Sink.head)

      whenReady(parsedResult) {
        _.mustBe(Left(NoElementFound("messageSender")))
      }
    }
  }

  MessageType.departureRequestValues.foreach {
    messageType =>
      "OfficeOfDeparture parser" - new Setup {
        s"when provided with a valid ${messageType.code} message it extracts the OfficeOfDeparture" in {
          val stream       = createParsingEventStream(messageWithoutMessageSender(messageType.rootNode))
          val parsedResult = stream.via(XmlParser.officeOfDepartureExtractor(messageType)).runWith(Sink.head)

          whenReady(parsedResult) {
            _.right.get mustBe referenceNumber
          }
        }

        s"when provided with a missing OfficeOfDeparture node in ${messageType.code} message it returns NoElementFound" in {
          val stream       = createParsingEventStream(messageWithoutOfficeOfDeparture(messageType.rootNode))
          val parsedResult = stream.via(XmlParser.officeOfDepartureExtractor(MessageType.DeclarationData)).runWith(Sink.head)

          whenReady(parsedResult) {
            _.mustBe(Left(NoElementFound("referenceNumber")))
          }
        }

        s"when provided with a missing ReferenceNumber node in ${messageType.code} message it returns NoElementFound" in {
          val stream       = createParsingEventStream(messageWithoutRefNumber(messageType.rootNode))
          val parsedResult = stream.via(XmlParser.officeOfDepartureExtractor(MessageType.DeclarationData)).runWith(Sink.head)

          whenReady(parsedResult) {
            _.mustBe(Left(NoElementFound("referenceNumber")))
          }
        }
      }

      s"MessageSenderElement parser should add the messageSender node with the movement Id in ${messageType.code} message" in new Setup {

        val stream = createStream(messageWithoutMessageSender(messageType.rootNode))

        val parsedResult = stream
          .via(XmlParsing.parser)
          .via(XmlParser.messageSenderWriter(messageType, messageSender)) // testing this
          .via(XmlWriting.writer)
          .fold(ByteString())(_ ++ _)
          .map(_.utf8String)
          .runWith(Sink.head)

        whenReady(parsedResult) {
          result =>
            XML.loadString(result) shouldBe messageWithMessageSender(messageType.rootNode)
        }
      }
  }

  trait Setup {

    val referenceNumber        = arbitraryOfficeOfDeparture.arbitrary.sample.get
    val messageSender          = arbitraryMessageSender.arbitrary.sample.get
    val preparationDateAndTime = arbitraryOffsetDateTime.arbitrary.sample.get.toLocalDateTime.format(DateTimeFormatter.ISO_DATE_TIME)

    def messageWithoutMessageSender(messageTypeNode: String): NodeSeq = {
      val strMessage =
        s"<$messageTypeNode><preparationDateAndTime>$preparationDateAndTime</preparationDateAndTime><CustomsOfficeOfDeparture><referenceNumber>${referenceNumber.value}</referenceNumber></CustomsOfficeOfDeparture></$messageTypeNode>"
      XML.loadString(strMessage)
    }

    def messageWithoutRefNumber(messageTypeNode: String): NodeSeq = {
      val strMessage =
        s"<$messageTypeNode><preparationDateAndTime>$preparationDateAndTime</preparationDateAndTime><CustomsOfficeOfDeparture><referenceNumber></referenceNumber></CustomsOfficeOfDeparture></$messageTypeNode>"
      XML.loadString(strMessage)
    }

    def messageWithoutOfficeOfDeparture(messageTypeNode: String): NodeSeq = {
      val strMessage =
        s"<$messageTypeNode><preparationDateAndTime>$preparationDateAndTime</preparationDateAndTime></$messageTypeNode>"
      XML.loadString(strMessage)
    }

    def messageWithMessageSender(messageTypeNode: String): NodeSeq = {
      val strMessage =
        s"<$messageTypeNode><messageSender>${messageSender.value}</messageSender><preparationDateAndTime>$preparationDateAndTime</preparationDateAndTime><CustomsOfficeOfDeparture><referenceNumber>${referenceNumber.value}</referenceNumber></CustomsOfficeOfDeparture></$messageTypeNode>"
      XML.loadString(strMessage)
    }

    val cc015cWithoutMessageSenderValue = {
      val strMessage =
        s"<CC015C><messageSender></messageSender><preparationDateAndTime>$preparationDateAndTime</preparationDateAndTime><CustomsOfficeOfDeparture><referenceNumber>${referenceNumber.value}</referenceNumber></CustomsOfficeOfDeparture></CC015C>"
      XML.loadString(strMessage)
    }

  }

}
