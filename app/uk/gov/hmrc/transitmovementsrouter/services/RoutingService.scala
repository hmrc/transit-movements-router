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

  val office = Sink.head[ParseResult[CustomsOffice]]

  private def officeSink(messageType: RequestMessageType): Sink[ByteString, Future[ParseResult[CustomsOffice]]] = Sink.fromGraph(
    GraphDSL.createGraph(office) {
      implicit builder => officeShape =>
        import GraphDSL.Implicits._

        val xmlParsing: FlowShape[ByteString, ParseEvent] = builder.add(XmlParsing.parser)
        val customsOffice: FlowShape[ParseEvent, ParseResult[CustomsOffice]] =
          builder.add(XmlParser.customsOfficeExtractor(messageType))
        xmlParsing ~> customsOffice ~> officeShape

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
  )(implicit hc: HeaderCarrier, ec: ExecutionContext): EitherT[Future, RoutingError, Unit] = {

    val materializedResult      = payload.runWith(officeSink(messageType))
    implicit val messageSender  = MessageSender(movementId, messageId)
    implicit val updatedPayload = payload.via(buildMessage(messageType, messageSender))

    val maybeACustomsOffice = {
      materializedResult flatMap {
        case Right(customsOffice) => postToEis(customsOffice)
        case Left(routingError)   => Future.successful(Left(routingError))
      }
    }

    EitherT(maybeACustomsOffice)
  }

  private def postToEis(
    office: CustomsOffice
  )(implicit messageSender: MessageSender, body: Source[ByteString, _], hc: HeaderCarrier): Future[Either[RoutingError, Unit]] =
    if (office.isGB) {
      messageConnectorProvider.gb.post(messageSender, body, hc)
    } else if (office.isXI) {
      messageConnectorProvider.xi.post(messageSender, body, hc)
    } else {
      Future.successful(Left(RoutingError.Unexpected(s"An unexpected error occurred - got a customs office value of: ${office.value}", None)))
    }
}
