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

package uk.gov.hmrc.transitmovements.router.services

import akka.stream.alpakka.xml.scaladsl._
import akka.stream.scaladsl.Sink
import akka.util.ByteString
import org.scalatest.concurrent.ScalaFutures.whenReady
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper
import uk.gov.hmrc.transitmovements.base.StreamTestHelpers._
import uk.gov.hmrc.transitmovements.base.TestActorSystem
import uk.gov.hmrc.transitmovementsrouter.models._
import uk.gov.hmrc.transitmovementsrouter.services.XmlParser

import scala.xml.Utility.trim
import scala.xml._

class XmlParserSpec extends AnyFreeSpec with TestActorSystem with Matchers {

  "MessageSender parser" - new Setup {
    "when provided with a valid message" in {
      val stream       = createParsingEventStream(cc015cValidGB)
      val parsedResult = stream.via(XmlParser.messageSenderExtractor).runWith(Sink.head)

      whenReady(parsedResult) {
        _.right.get mustBe MessageSenderId("GB1234")
      }
    }

    "when provided with a missing messageSender node" in {
      val stream       = createParsingEventStream(cc015cNoMessageSenderNode)
      val parsedResult = stream.via(XmlParser.messageSenderExtractor).runWith(Sink.head)

      whenReady(parsedResult) {
        _.isLeft
      }
    }

    "when provided with a no value for messageSender node" in {
      val stream       = createParsingEventStream(cc015cNoMessageSenderValue)
      val parsedResult = stream.via(XmlParser.messageSenderExtractor).runWith(Sink.head)

      whenReady(parsedResult) {
        _.isLeft
      }
    }
  }

  "OfficeOfDestination parser" - new Setup {
    "when provided with a valid message" in {
      val stream       = createParsingEventStream(cc015cValidGB)
      val parsedResult = stream.via(XmlParser.officeOfDestinationExtractor).runWith(Sink.head)

      whenReady(parsedResult) {
        _.right.get mustBe OfficeOfDestination("GB6789")
      }
    }

    "when provided with a missing OfficeOfDestination node" in {
      val stream       = createParsingEventStream(cc015cNoOfficeOfDestination)
      val parsedResult = stream.via(XmlParser.officeOfDestinationExtractor).runWith(Sink.head)

      whenReady(parsedResult) {
        _.isLeft
      }
    }

    "when provided with a missing ReferenceNumber node" in {
      val stream       = createParsingEventStream(cc015cNoRefNumber)
      val parsedResult = stream.via(XmlParser.officeOfDestinationExtractor).runWith(Sink.head)

      whenReady(parsedResult) {
        _.isLeft
      }
    }
  }

  "MessageSenderElement parser should add the messageSender node with the movement Id" in new Setup {

    val stream = createStream(cc015cNoMessageSenderNode)

    val parsedResult = stream
      .via(XmlParsing.parser)
      .via(XmlParser.messageSenderWriter(MovementMessageId("GB123456789"))) // testing this
      .via(XmlWriting.writer)
      .fold(ByteString())(_ ++ _)
      .map(_.utf8String)
      .runWith(Sink.head)

    whenReady(parsedResult) {
      result =>
        trim(XML.loadString(result)) shouldBe trim(cc015cWithExpectedMessageSenderNode.head)
    }
  }

  trait Setup {

    val cc015cInsertMessageSender =
      """<CC015C>
        <preparationDateAndTime>2022-05-25T09:37:04</preparationDateAndTime>
        <CustomsOfficeOfDestinationDeclared>
          <referenceNumber>GB6789</referenceNumber>
        </CustomsOfficeOfDestinationDeclared>
      </CC015C>""".stripMargin

    val cc015cValidGB: NodeSeq =
      <CC015C>
        <messageSender>GB1234</messageSender>
        <preparationDateAndTime>2022-05-25T09:37:04</preparationDateAndTime>
        <CustomsOfficeOfDestinationDeclared>
          <referenceNumber>GB6789</referenceNumber>
        </CustomsOfficeOfDestinationDeclared>
      </CC015C>

    val cc015cValidXI: NodeSeq =
      <CC015C>
        <messageSender>GB1234</messageSender>
        <preparationDateAndTime>2022-05-25T09:37:04</preparationDateAndTime>
        <CustomsOfficeOfDestinationDeclared>
          <referenceNumber>XI98765</referenceNumber>
        </CustomsOfficeOfDestinationDeclared>
      </CC015C>

    val cc015cNoMessageSenderNode: NodeSeq =
      <CC015C>
        <preparationDateAndTime>2022-05-25T09:37:04</preparationDateAndTime>
      </CC015C>

    val cc015cWithExpectedMessageSenderNode: NodeSeq =
      <CC015C>
        <messageSender>GB123456789</messageSender>
        <preparationDateAndTime>2022-05-25T09:37:04</preparationDateAndTime>
      </CC015C>

    val cc015cMessageSenderNode: NodeSeq =
      <CC015C>
        <messageSender></messageSender>
        <preparationDateAndTime>2022-05-25T09:37:04</preparationDateAndTime>
      </CC015C>

    val cc015cNoMessageSenderValue: NodeSeq =
      <CC015C>
        <messageSender></messageSender>
        <preparationDateAndTime>2022-05-25T09:37:04</preparationDateAndTime>
      </CC015C>

    val cc015cNoRefNumber: NodeSeq =
      <CC015C>
        <messageSender>GB1234</messageSender>
        <preparationDateAndTime>2022-05-25T09:37:04</preparationDateAndTime>
        <CustomsOfficeOfDestinationDeclared/>
      </CC015C>

    val cc015cNoOfficeOfDestination: NodeSeq =
      <CC015C>
        <messageSender>GB1234</messageSender>
        <preparationDateAndTime>2022-05-25T09:37:04</preparationDateAndTime>
      </CC015C>

  }

}
