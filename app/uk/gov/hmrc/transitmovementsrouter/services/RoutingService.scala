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
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.google.inject._
import uk.gov.hmrc.transitmovementsrouter.models.MovementMessageId
import uk.gov.hmrc.transitmovementsrouter.services.XmlParser.ParseResult

@ImplementedBy(classOf[RoutingServiceImpl])
trait RoutingService {
  def submitDeclaration(messageId: MovementMessageId, payload: Source[ByteString, _]): Flow[ByteString, ParseResult[_], NotUsed]
}

class RoutingServiceImpl @Inject() (implicit materializer: Materializer) extends RoutingService with XmlParsingServiceHelpers {

  private def buildMessage(messageId: MovementMessageId): Flow[ByteString, ParseEvent, NotUsed] =
    Flow.fromGraph(
      GraphDSL.create() {
        implicit builder =>
          import GraphDSL.Implicits._

          val xmlParsing: FlowShape[ByteString, ParseEvent]         = builder.add(XmlParsing.parser)
          val insertSenderWriter: FlowShape[ParseEvent, ParseEvent] = builder.add(XmlParser.messageSenderWriter(messageId))
          val officeOfDestination                                   = builder.add(XmlParser.officeOfDestinationExtractor)
          xmlParsing ~> insertSenderWriter

          FlowShape(xmlParsing.in, insertSenderWriter.out)
      }
    )

  override def submitDeclaration(messageId: MovementMessageId, payload: Source[ByteString, _]): Flow[ByteString, XmlParser.ParseResult[_], NotUsed] = ???
//    payload.via(buildMessage(messageId)).run(Sink.head)
}
