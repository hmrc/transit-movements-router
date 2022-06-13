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
  def submitDeclaration(messageId: MovementMessageId, payload: Source[ByteString, _]): (Flow[ByteString, ParseResult[_], OfficeOfDestination])
}

class RoutingServiceImpl @Inject() (implicit materializer: Materializer) extends XmlParsingServiceHelpers {

  val office = Sink.head[ParseResult[OfficeOfDestination]]

  val officeOfDestinationSink: Sink[ByteString, Future[ParseResult[OfficeOfDestination]]] = Sink.fromGraph(
    GraphDSL.createGraph(office) {
      implicit builder => officeShape =>
        import GraphDSL.Implicits._

        val xmlParsing: FlowShape[ByteString, ParseEvent]                                = builder.add(XmlParsing.parser)
        val officeOfDestination: FlowShape[ParseEvent, ParseResult[OfficeOfDestination]] = builder.add(XmlParser.officeOfDestinationExtractor)

        xmlParsing ~> officeOfDestination ~> officeShape

        SinkShape(xmlParsing.in)
    }
  )

  def buildMessage(messageId: MovementMessageId): Flow[ByteString, ParseEvent, NotUsed] =
    Flow.fromGraph(
      GraphDSL.create() {
        implicit builder =>
          import GraphDSL.Implicits._

          val xmlParsing: FlowShape[ByteString, ParseEvent]         = builder.add(XmlParsing.parser)
          val insertSenderWriter: FlowShape[ParseEvent, ParseEvent] = builder.add(XmlParser.messageSenderWriter(messageId))

          xmlParsing ~> insertSenderWriter

          FlowShape(xmlParsing.in, insertSenderWriter.out)
      }
    )

  def submitDeclaration(
    messageId: MovementMessageId,
    payload: Source[ByteString, _]
  ): (FlowShape[ByteString, ParseEvent], Future[ParseResult[OfficeOfDestination]]) = {

    val officeOfDestination: Future[ParseResult[OfficeOfDestination]] = payload.toMat(officeOfDestinationSink)(Keep.right).run()

    val updatedPayload = payload.via(buildMessage(messageId)).to(????)
    (updatedPayload, officeOfDestination)
  }
}
