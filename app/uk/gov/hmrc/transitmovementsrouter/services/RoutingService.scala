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
import uk.gov.hmrc.transitmovementsrouter.models._
import uk.gov.hmrc.transitmovementsrouter.services.XmlParser.ParseResult

import scala.concurrent.Future

@ImplementedBy(classOf[RoutingServiceImpl])
trait RoutingService {

  def submitDeclaration(
    movementType: MovementType,
    movementId: MovementId,
    messageId: MessageId,
    payload: Source[ByteString, _]
  ): (Source[ByteString, _], Future[ParseResult[OfficeOfDeparture]])
}

class RoutingServiceImpl @Inject() (implicit materializer: Materializer) extends RoutingService with XmlParsingServiceHelpers {

  val office = Sink.head[ParseResult[OfficeOfDeparture]]

  private val officeOfDepartureSink: Sink[ByteString, Future[ParseResult[OfficeOfDeparture]]] = Sink.fromGraph(
    GraphDSL.createGraph(office) {
      implicit builder => officeShape =>
        import GraphDSL.Implicits._

        val xmlParsing: FlowShape[ByteString, ParseEvent]                            = builder.add(XmlParsing.parser)
        val officeOfDeparture: FlowShape[ParseEvent, ParseResult[OfficeOfDeparture]] = builder.add(XmlParser.officeOfDepartureExtractor)
        xmlParsing ~> officeOfDeparture ~> officeShape

        SinkShape(xmlParsing.in)
    }
  )

  def buildMessage(messageId: MessageId): Flow[ByteString, ByteString, NotUsed] =
    Flow.fromGraph(
      GraphDSL.create() {
        implicit builder =>
          import GraphDSL.Implicits._

          val xmlParsing: FlowShape[ByteString, ParseEvent]         = builder.add(XmlParsing.parser)
          val insertSenderWriter: FlowShape[ParseEvent, ParseEvent] = builder.add(XmlParser.messageSenderWriter(messageId))
          val toByteString                                          = builder.add(XmlWriting.writer)
          xmlParsing ~> insertSenderWriter ~> toByteString

          FlowShape(xmlParsing.in, toByteString.out)
      }
    )

  override def submitDeclaration(
    movementType: MovementType,
    movementId: MovementId,
    messageId: MessageId,
    payload: Source[ByteString, _]
  ): (Source[ByteString, _], Future[ParseResult[OfficeOfDeparture]]) = {

    val officeOfDeparture: Future[ParseResult[OfficeOfDeparture]] = payload.toMat(officeOfDepartureSink)(Keep.right).run()

    val updatedPayload: Source[ByteString, _] = payload.via(buildMessage(messageId))

    (updatedPayload, officeOfDeparture)
  }
}
