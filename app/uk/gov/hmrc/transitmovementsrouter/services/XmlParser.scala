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

import akka.NotUsed
import akka.stream.alpakka.xml.Characters
import akka.stream.alpakka.xml.EndElement
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.Keep
import akka.stream.scaladsl.Sink
import uk.gov.hmrc.transitmovementsrouter.models.EoriNumber
import uk.gov.hmrc.transitmovementsrouter.models.MessageSenderId
import uk.gov.hmrc.transitmovementsrouter.models.MovementMessageId
import akka.stream.alpakka.xml.ParseEvent
import akka.stream.alpakka.xml.StartElement
import akka.stream.alpakka.xml.javadsl.XmlWriting
import akka.stream.alpakka.xml.scaladsl.XmlParsing
import akka.util.ByteString

import scala.concurrent.Future

object XmlParser extends XmlParsingServiceHelpers {

  val messageSenderExtractor: Flow[ParseEvent, ParseResult[MessageSenderId], NotUsed] = XmlParsing
    .subtree("CC015C" :: "messageSender" :: Nil)
    .collect {
      case element if element.getTextContent.nonEmpty => MessageSenderId(element.getTextContent)
    }
    .single("messageSender")

  val OfficeOfDestinationExtractor: Flow[ParseEvent, ParseResult[EoriNumber], NotUsed] = XmlParsing
    .subtree("CC015C" :: "CustomsOfficeOfDestinationDeclared" :: "referenceNumber" :: Nil)
    .collect {
      case element if element.getTextContent.nonEmpty => EoriNumber(element.getTextContent)
    }
    .single("referenceNumber")

  def messageSenderWriter(messageId: MovementMessageId): Flow[ParseEvent, ParseEvent, NotUsed] = Flow[ParseEvent]
    .mapConcat(
      element => if (isElement("CC015C", element)) Seq(element, StartElement("messageSender"), Characters("messageSenderCharacters"), EndElement("messageSender")) else Seq(element)
    )
    // .via(XmlWriting.writer)
    //.map[String](_.utf8String)
    //.toMat(Sink.fold[String, String]("")(_ + _))(Keep.right)

  def messageSenderWriter2(messageId: MovementMessageId): Sink[ByteString, NotUsed] = Flow[ByteString]
    .map[String](_.utf8String)
    .map(
      s => s.split('>').toList.map(_ + ">")
    )
    .mapConcat(identity)
    .map(buildLine(messageId.message, _))
    .to(Sink.foreach(println))

  private def isElement(name: String, event: ParseEvent): Boolean = event match {
    case s: StartElement if s.localName == name => true
    case _                                      => false
  }

  private def buildLine(element: String, message: String) =
    if (element == "<CC015C>") element + "\n<messageSender>$message</messageSender>" else element
}
