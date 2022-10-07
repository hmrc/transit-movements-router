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
import akka.stream.scaladsl.Keep
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

import scala.concurrent.Future

@ImplementedBy(classOf[RoutingServiceImpl])
trait RoutingService {

  def submitMessage(
    movementType: MovementType,
    movementId: MovementId,
    messageId: MessageId,
    messageType: MessageType,
    payload: Source[ByteString, _]
  )(implicit hc: HeaderCarrier): EitherT[Future, RoutingError, Unit]

}

class RoutingServiceImpl @Inject() (messageConnectorProvider: EISConnectorProvider)(implicit materializer: Materializer)
    extends RoutingService
    with XmlParsingServiceHelpers
    with Logging {

  val office = Sink.head[ParseResult[CustomsOffice]]

  private def officeOfDepartureSink(messageType: MessageType): Sink[ByteString, Future[ParseResult[CustomsOffice]]] = Sink.fromGraph(
    GraphDSL.createGraph(office) {
      implicit builder => officeShape =>
        import GraphDSL.Implicits._

        val xmlParsing: FlowShape[ByteString, ParseEvent] = builder.add(XmlParsing.parser)
        val customsOffice: FlowShape[ParseEvent, ParseResult[CustomsOffice]] =
          builder.add(XmlParser.customsOfficeExtractor(messageType, "CustomsOfficeOfDeparture"))
        xmlParsing ~> customsOffice ~> officeShape

        SinkShape(xmlParsing.in)
    }
  )

  private def officeOfDestinationActualSink(messageType: MessageType): Sink[ByteString, Future[ParseResult[CustomsOffice]]] =
    Sink.fromGraph(
      GraphDSL.createGraph(office) {
        implicit builder => officeShape =>
          import GraphDSL.Implicits._

          val xmlParsing    = builder.add(XmlParsing.parser)
          val customsOffice = builder.add(XmlParser.customsOfficeExtractor(messageType, "CustomsOfficeOfDestinationActual"))
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
    messageType: MessageType,
    payload: Source[ByteString, _]
  )(implicit hc: HeaderCarrier): EitherT[Future, RoutingError, Unit] = {

    val sink: Either[RoutingError, Sink[ByteString, Future[ParseResult[CustomsOffice]]]] = messageType match {
      case _: ArrivalRequestMessageType   => Right(officeOfDestinationActualSink(messageType))
      case _: DepartureRequestMessageType => Right(officeOfDepartureSink(messageType))
      case msgTypeError =>
        val message = s"An unexpected error occurred - got a ${msgTypeError.code}"
        logger.error(message)
        Left(RoutingError.Unexpected(message, None))
    }

    val materializedResult: Either[RoutingError, Future[ParseResult[CustomsOffice]]] = sink
      .map(
        payload
          .toMat(_)(Keep.right)
          .run()
      )

    implicit val messageSender  = MessageSender(movementId, messageId)
    implicit val updatedPayload = payload.via(buildMessage(messageType, messageSender))

    val maybeACustomsOffice = materializedResult match {
      case Right(customsOffice) => post(customsOffice)
      case Left(routingError)   => Future.successful(Left(routingError))
    }

    EitherT(maybeACustomsOffice)
  }

  private def post(
    office: Future[ParseResult[CustomsOffice]]
  )(implicit messageSender: MessageSender, body: Source[ByteString, _], hc: HeaderCarrier): Future[Either[RoutingError, Unit]] = {
    import concurrent.ExecutionContext.Implicits.global
    office.flatMap {
      case Right(office) =>
        postToEis(office).map {
          case Right(_)    => Right(())
          case Left(error) => Left(error)
        }
      case Left(routingError) =>
        logger.error(s"Unable to extract customs office: $routingError")
        Future.successful(Left(routingError));
    }
  }

  private def postToEis(
    office: CustomsOffice
  )(implicit messageSender: MessageSender, body: Source[ByteString, _], hc: HeaderCarrier): Future[Either[RoutingError, Unit]] =
    if (office.isGB) {
      messageConnectorProvider.gb.post(messageSender, body, hc)
    } else {
      messageConnectorProvider.xi.post(messageSender, body, hc)
    }
}
