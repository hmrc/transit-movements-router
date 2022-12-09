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
import akka.stream._
import akka.stream.alpakka.xml.ParseEvent
import akka.stream.alpakka.xml.scaladsl._
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.GraphDSL
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.google.inject._
import play.api.Logging
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.transitmovementsrouter.connectors.EISConnectorProvider
import uk.gov.hmrc.transitmovementsrouter.models._
import uk.gov.hmrc.transitmovementsrouter.services.error.RoutingError
import cats.data.EitherT
import uk.gov.hmrc.transitmovementsrouter.connectors.EISConnector

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

@ImplementedBy(classOf[RoutingServiceImpl])
trait RoutingService {

  def submitMessage(
    movementType: MovementType,
    movementId: MovementId,
    messageId: MessageId,
    messageType: RequestMessageType,
    payload: Source[ByteString, _]
  )(implicit hc: HeaderCarrier, ec: ExecutionContext): EitherT[Future, RoutingError, Unit]

}

class RoutingServiceImpl @Inject() (messageConnectorProvider: EISConnectorProvider)(implicit materializer: Materializer)
    extends RoutingService
    with XmlParsingServiceHelpers
    with Logging {

  private val connectorSinkShape = Sink.head[ParseResult[EISConnector]]

  private def officeExtractor(messageType: RequestMessageType): Sink[ByteString, Future[ParseResult[EISConnector]]] = Sink.fromGraph(
    GraphDSL.createGraph(connectorSinkShape) {
      implicit builder => officeShape =>
        import GraphDSL.Implicits._

        val xmlParsing: FlowShape[ByteString, ParseEvent] = builder.add(XmlParsing.parser)
        val customsOffice: FlowShape[ParseEvent, ParseResult[CustomsOffice]] =
          builder.add(XmlParser.customsOfficeExtractor(messageType))
        val validateOffice: FlowShape[ParseResult[CustomsOffice], ParseResult[EISConnector]] =
          builder.add(Flow.fromFunction(selectConnector(_, messageType)))
        xmlParsing ~> customsOffice ~> validateOffice ~> officeShape

        SinkShape(xmlParsing.in)
    }
  )

  def buildMessage(messageType: MessageType, messageSender: MessageSender): Flow[ByteString, ByteString, NotUsed] =
    Flow.fromGraph(
      GraphDSL.create() {
        implicit builder =>
          import GraphDSL.Implicits._

          val xmlParsing: FlowShape[ByteString, ParseEvent]         = builder.add(XmlParsing.parser)
          val insertSenderWriter: FlowShape[ParseEvent, ParseEvent] = builder.add(XmlParser.messageSenderWriter(messageType, messageSender))
          val toByteString                                          = builder.add(XmlWriting.writer)
          xmlParsing ~> insertSenderWriter ~> toByteString

          FlowShape(xmlParsing.in, toByteString.out)
      }
    )

  override def submitMessage(
    movementType: MovementType,
    movementId: MovementId,
    messageId: MessageId,
    messageType: RequestMessageType,
    payload: Source[ByteString, _]
  )(implicit hc: HeaderCarrier, ec: ExecutionContext): EitherT[Future, RoutingError, Unit] =
    for {
      connector <- EitherT[Future, RoutingError, EISConnector](payload.runWith(officeExtractor(messageType)))
      messageSender  = MessageSender(movementId, messageId)
      updatedPayload = payload.via(buildMessage(messageType, messageSender))
      _ <- EitherT(connector.post(messageSender, updatedPayload, hc))
    } yield ()

  def selectConnector(maybeOffice: ParseResult[CustomsOffice], messageType: RequestMessageType): ParseResult[EISConnector] =
    maybeOffice.flatMap {
      office =>
        if (office.isGB) Right(messageConnectorProvider.gb)
        else if (office.isXI) Right(messageConnectorProvider.xi)
        else Left(RoutingError.UnrecognisedOffice(s"Did not recognise office: ${office.value}", office, messageType.officeNode))
    }

}
