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
import akka.stream.alpakka.xml.scaladsl.XmlParsing
import akka.stream.alpakka.xml.scaladsl.XmlWriting
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
  def submitDeclaration(messageId: MovementMessageId, payload: Source[ByteString, _]): (Source[ByteString, _], Future[ParseResult[OfficeOfDestination]])
}

class RoutingServiceImpl @Inject() (implicit materializer: Materializer) extends RoutingService with XmlParsingServiceHelpers {

  val office = Sink.head[ParseResult[OfficeOfDestination]]

  val officeOfDestinationSink: Sink[ByteString, Future[ParseResult[OfficeOfDestination]]] = Sink.fromGraph(
    GraphDSL.createGraph(office) {
      implicit builder => officeShape =>
        import GraphDSL.Implicits._

        val xmlParsing: FlowShape[ByteString, ParseEvent]                                = builder.add(XmlParsing.parser)
        val officeOfDestination: FlowShape[ParseEvent, ParseResult[OfficeOfDestination]] = builder.add(XmlParser.officeOfDepartureExtractor)

        xmlParsing ~> officeOfDestination ~> officeShape

        SinkShape(xmlParsing.in)
    }
  )

  def buildMessage(messageId: MovementMessageId): Flow[ByteString, ByteString, NotUsed] =
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
    messageId: MovementMessageId,
    payload: Source[ByteString, _]
  ): (Source[ByteString, _], Future[ParseResult[OfficeOfDestination]]) = {

    val officeOfDestination: Future[ParseResult[OfficeOfDestination]] = payload.toMat(officeOfDestinationSink)(Keep.right).run()

    val updatedPayload: Source[ByteString, _] = payload.via(buildMessage(messageId))

    (updatedPayload, officeOfDestination)
  }
}
