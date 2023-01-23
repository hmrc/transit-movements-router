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

import akka.NotUsed
import akka.stream.alpakka.xml._
import akka.stream.alpakka.xml.scaladsl.XmlParsing
import akka.stream.scaladsl.Flow
import uk.gov.hmrc.transitmovementsrouter.models._

object XmlParser extends XmlParsingServiceHelpers {

  val messageSenderExtractor: Flow[ParseEvent, ParseResult[MessageSender], NotUsed] = XmlParsing
    .subtree("CC015C" :: "messageSender" :: Nil)
    .collect {
      case element if element.getTextContent.nonEmpty => MessageSender(element.getTextContent)
    }
    .single("messageSender")

  def customsOfficeExtractor(
    messageType: RequestMessageType
  ): Flow[ParseEvent, ParseResult[CustomsOffice], NotUsed] = XmlParsing
    .subtree(messageType.rootNode :: messageType.officeNode :: "referenceNumber" :: Nil)
    .collect {
      case element if element.getTextContent.nonEmpty => CustomsOffice(element.getTextContent)
    }
    .single("referenceNumber")

  private def isElement(name: String, event: ParseEvent): Boolean = event match {
    case s: StartElement if s.localName == name => true
    case _                                      => false
  }

}
