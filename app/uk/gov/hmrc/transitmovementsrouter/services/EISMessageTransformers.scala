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
import uk.gov.hmrc.transitmovementsrouter.services.state.WrappingState

@ImplementedBy(classOf[EISMessageTransformersImpl])
trait EISMessageTransformers {

  def wrap: Flow[ByteString, ByteString, _]

  def unwrap: Flow[ByteString, ByteString, _]

}

@Singleton
class EISMessageTransformersImpl extends EISMessageTransformers {

  private val WRAPPED_MESSAGE_TYPE_PREFIX = "txd"
  private val WRAPPED_MESSAGE_ROOT_PREFIX = "n1"
  private val UNWRAPPED_PREFIX            = "ncts"
  private val NCTS_NAMESPACE_URL          = "http://ncts.dgtaxud.ec"
  private val XSI_NAMESPACE_URL           = "http://www.w3.org/2001/XMLSchema-instance"
  private val EIS_NAMESPACE_URL           = "http://www.hmrc.gov.uk/eis/ncts5/v1"
  private val TRADER_CHANNEL_RESPONSE     = "TraderChannelResponse"
  private val TRADER_CHANNEL_SUBMISSION   = "TraderChannelSubmission"

  private val WRAPPED_ATTRIBUTES: List[Attribute] = List(
    Attribute("schemaLocation", "http://www.hmrc.gov.uk/eis/ncts5/v1 EIS_WrapperV10_TraderChannelSubmission-51.8.xsd", Some("xsi"), Some(XSI_NAMESPACE_URL))
  )

  private val WRAPPED_NAMESPACES: List[Namespace] = List(
    Namespace(NCTS_NAMESPACE_URL, Some(WRAPPED_MESSAGE_TYPE_PREFIX)),
    Namespace(EIS_NAMESPACE_URL, Some(WRAPPED_MESSAGE_ROOT_PREFIX)),
    Namespace(XSI_NAMESPACE_URL, Some("xsi"))
  )

  private val messageType = """CC\d{3}[A-Z]""".r

  // Unwrapping

  private def lookingForWrappedElement(parseEvent: ParseEvent): (UnwrappingState, ParseEvent) = parseEvent match {
    case StartElement(TRADER_CHANNEL_RESPONSE, _, _, _, _) =>
      (LookingForMessageTypeElement, parseEvent)
    case x: StartElement => throw new IllegalStateException(s"First element should be local name TraderChannelResponse, not ${x.localName}")
    case element         => (LookingForWrappedElement, element)
  }

  private def lookingForMessageTypeElement(parseEvent: ParseEvent): (UnwrappingState, ParseEvent) = parseEvent match {
    case StartElement(name @ messageType(), attributes, Some(_), _, _) =>
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
          Some(UNWRAPPED_PREFIX),
          Some(NCTS_NAMESPACE_URL),
          List(Namespace(NCTS_NAMESPACE_URL, Some(UNWRAPPED_PREFIX)))
        )
      )
    case element: StartElement => throw new IllegalStateException(s"Expecting a message type root (got ${element.localName})")
    case event                 => (LookingForMessageTypeElement, event)
  }

  private def completingTransformation(state: FoundMessageType, parseEvent: ParseEvent): (UnwrappingState, ParseEvent) = parseEvent match {
    case EndElement(state.root) => (state, EndElement(s"$UNWRAPPED_PREFIX:${state.root}"))
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
      .via(XmlParsing.subslice(TRADER_CHANNEL_RESPONSE :: Nil))
      .via(XmlWriting.writer)

  // Wrapping

  private lazy val wrappingHead: ParseEvent =
    StartElement(TRADER_CHANNEL_SUBMISSION, WRAPPED_ATTRIBUTES, Some(WRAPPED_MESSAGE_ROOT_PREFIX), Some(EIS_NAMESPACE_URL), WRAPPED_NAMESPACES)

  private lazy val wrappingTail: ParseEvent = EndElement(s"$WRAPPED_MESSAGE_ROOT_PREFIX:$TRADER_CHANNEL_SUBMISSION")

  private def lookingForMessageType(event: ParseEvent): (WrappingState, Seq[ParseEvent]) = event match {
    case StartElement(localName @ messageType(), attributesList, Some(UNWRAPPED_PREFIX), _, _) =>
      (
        WrappingState.FoundMessageType(localName),
        Seq(wrappingHead, StartElement(localName, attributesList, Some(WRAPPED_MESSAGE_TYPE_PREFIX), Some(NCTS_NAMESPACE_URL), List.empty))
      )
    case x: StartElement => throw new IllegalStateException(s"Root tag was not the message type (found ${x.localName})")
    case x               => (WrappingState.LookingForMessageType, Seq(x))
  }

  private def foundMessageType(state: WrappingState.FoundMessageType, event: ParseEvent): (WrappingState, Seq[ParseEvent]) = event match {
    case element @ EndElement(messageType()) =>
      (state, Seq(EndElement(s"$WRAPPED_MESSAGE_TYPE_PREFIX:${element.localName}"), wrappingTail))
    case x => (state, Seq(x))
  }

  lazy val wrap: Flow[ByteString, ByteString, _] =
    Flow[ByteString]
      .via(XmlParsing.parser)
      .statefulMap[WrappingState, Seq[ParseEvent]](
        () => WrappingState.LookingForMessageType
      )(
        (state, event) =>
          state match {
            case WrappingState.LookingForMessageType => lookingForMessageType(event)
            case s: WrappingState.FoundMessageType   => foundMessageType(s, event)
          },
        _ => None
      )
      .mapConcat(identity)
      .via(XmlWriting.writer)

}
