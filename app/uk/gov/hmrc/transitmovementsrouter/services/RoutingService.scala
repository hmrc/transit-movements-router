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

@Singleton
class RoutingServiceImpl @Inject() (messageConnectorProvider: EISConnectorProvider)(implicit materializer: Materializer)
    extends RoutingService
    with XmlParsingServiceHelpers
    with Logging {

  private val connectorSinkShape = Sink.head[ParseResult[EISConnector]]

  private def eisConnectorSelector(messageType: RequestMessageType): Sink[ByteString, Future[ParseResult[EISConnector]]] = Sink.fromGraph(
    GraphDSL.createGraph(connectorSinkShape) {
      implicit builder => sink =>
        import GraphDSL.Implicits._

        val xmlParsing: FlowShape[ByteString, ParseEvent] = builder.add(XmlParsing.parser)
        val customsOfficeSelector: FlowShape[ParseEvent, ParseResult[CustomsOffice]] =
          builder.add(XmlParser.customsOfficeExtractor(messageType))
        val eisConnectorSelector: FlowShape[ParseResult[CustomsOffice], ParseResult[EISConnector]] =
          builder.add(Flow.fromFunction(selectConnector(_, messageType)))
        xmlParsing ~> customsOfficeSelector ~> eisConnectorSelector ~> sink

        SinkShape(xmlParsing.in)
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
      connector <- EitherT[Future, RoutingError, EISConnector](payload.runWith(eisConnectorSelector(messageType)))
      _         <- EitherT(connector.post(payload, hc))
    } yield ()

  def selectConnector(maybeOffice: ParseResult[CustomsOffice], messageType: RequestMessageType): ParseResult[EISConnector] =
    maybeOffice.flatMap {
      office =>
        if (office.isGB) Right(messageConnectorProvider.gb)
        else if (office.isXI) Right(messageConnectorProvider.xi)
        else Left(RoutingError.UnrecognisedOffice(s"Did not recognise office: ${office.value}", office, messageType.officeNode))
    }

}
