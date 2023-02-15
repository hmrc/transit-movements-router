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

import akka.stream.alpakka.xml.Attribute
import akka.stream.alpakka.xml.EndElement
import akka.stream.alpakka.xml.Namespace
import akka.stream.alpakka.xml.ParseEvent
import akka.stream.alpakka.xml.StartElement
import akka.stream.alpakka.xml.scaladsl.XmlParsing
import akka.stream.alpakka.xml.scaladsl.XmlWriting
import akka.stream.scaladsl.Flow
import akka.util.ByteString
import com.google.inject.ImplementedBy
import com.google.inject.Singleton
import uk.gov.hmrc.transitmovementsrouter.services.state.UnwrappingState
import uk.gov.hmrc.transitmovementsrouter.services.state.UnwrappingState.FoundMessageType
import uk.gov.hmrc.transitmovementsrouter.services.state.UnwrappingState.LookingForMessageTypeElement
import uk.gov.hmrc.transitmovementsrouter.services.state.UnwrappingState.LookingForWrappedElement

@ImplementedBy(classOf[EISMessageTransformersImpl])
trait EISMessageTransformers {

  def unwrap: Flow[ByteString, ByteString, _]

}

@Singleton
class EISMessageTransformersImpl extends EISMessageTransformers {

  private val messageType = """CC\d{3}[A-Z]""".r

  private def lookingForWrappedElement(parseEvent: ParseEvent): (UnwrappingState, ParseEvent) = parseEvent match {
    case StartElement("TraderChannelResponse", _, Some("n1"), _, _) => (LookingForMessageTypeElement, parseEvent)
    case x: StartElement                                            => throw new IllegalStateException(s"First element should be a n1:TraderChannelResponse, not ${x.localName}")
    case element                                                    => (LookingForWrappedElement, element)
  }

  private def lookingForMessageTypeElement(parseEvent: ParseEvent): (UnwrappingState, ParseEvent) = parseEvent match {
    case StartElement(name @ messageType(), attributes, Some("txd"), _, _) =>
      val phaseId = attributes
        .find(
          a => a.name == "PhaseID"
        )
        .map(_.value)
        .getOrElse("NCTS5.1")

      (
        UnwrappingState.FoundMessageType(name),
        // Interestingly, the unit tests were happy with an empty list of namespaces, but
        // the integration tests were not. So we duplicate the ncts entry in the list.
        StartElement(
          name,
          List(Attribute("PhaseID", phaseId)),
          Some("ncts"),
          Some("http://ncts.dgtaxud.ec"),
          List(Namespace("http://ncts.dgtaxud.ec", Some("ncts")))
        )
      )
    case element: StartElement => throw new IllegalStateException(s"Expecting a message type root (got ${element.localName}")
    case event                 => (LookingForMessageTypeElement, event)
  }

  private def completingTransformation(state: FoundMessageType, parseEvent: ParseEvent): (UnwrappingState, ParseEvent) = parseEvent match {
    case EndElement(state.root) => (state, EndElement(s"ncts:${state.root}"))
    case element                => (state, element)
  }

  lazy val unwrap: Flow[ByteString, ByteString, _] =
    Flow[ByteString]
      .via(XmlParsing.parser)
      .statefulMap[UnwrappingState, ParseEvent](
        () => LookingForWrappedElement
      )(
        (state, event) =>
          state match {
            case LookingForWrappedElement     => lookingForWrappedElement(event)
            case LookingForMessageTypeElement => lookingForMessageTypeElement(event)
            case state: FoundMessageType      => completingTransformation(state, event)
          },
        _ => None
      )
      .via(XmlParsing.subslice("TraderChannelResponse" :: Nil))
      .via(XmlWriting.writer)

}
