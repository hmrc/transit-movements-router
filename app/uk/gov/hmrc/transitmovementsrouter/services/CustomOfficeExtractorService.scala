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

import org.apache.pekko.stream.FlowShape
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.SinkShape
import org.apache.pekko.stream.connectors.xml.ParseEvent
import org.apache.pekko.stream.connectors.xml.scaladsl.XmlParsing
import org.apache.pekko.stream.scaladsl.Flow
import org.apache.pekko.stream.scaladsl.GraphDSL
import org.apache.pekko.stream.scaladsl.Sink
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import cats.data.EitherT
import com.google.inject.ImplementedBy
import com.google.inject.Inject
import com.google.inject.Singleton
import uk.gov.hmrc.transitmovementsrouter.models.CustomsOffice
import uk.gov.hmrc.transitmovementsrouter.models.RequestMessageType
import uk.gov.hmrc.transitmovementsrouter.models.errors.CustomOfficeExtractorError
import uk.gov.hmrc.transitmovementsrouter.services.XmlParser.ParseResult

import scala.concurrent.Future

@ImplementedBy(classOf[CustomOfficeExtractorServiceImpl])
trait CustomOfficeExtractorService {
  def extractCustomOffice(source: Source[ByteString, _], messageType: RequestMessageType): EitherT[Future, CustomOfficeExtractorError, CustomsOffice]

}

@Singleton
class CustomOfficeExtractorServiceImpl @Inject() (implicit mat: Materializer) extends CustomOfficeExtractorService {

  override def extractCustomOffice(source: Source[ByteString, _], messageType: RequestMessageType): EitherT[Future, CustomOfficeExtractorError, CustomsOffice] =
    EitherT(source.runWith(customOfficeExtractor(messageType)))

  private val officeSinkShape = Sink.head[ParseResult[CustomsOffice]]

  private def customOfficeExtractor(messageType: RequestMessageType): Sink[ByteString, Future[ParseResult[CustomsOffice]]] =
    Sink.fromGraph(GraphDSL.createGraph(officeSinkShape) {
      implicit builder => sink =>
        import GraphDSL.Implicits._

        val xmlParsing: FlowShape[ByteString, ParseEvent] = builder.add(XmlParsing.parser)
        val customsOfficeSelector: FlowShape[ParseEvent, ParseResult[CustomsOffice]] =
          builder.add(XmlParser.customsOfficeExtractor(messageType))
        val validCustomOfficeType: FlowShape[ParseResult[CustomsOffice], ParseResult[CustomsOffice]] =
          builder.add(Flow.fromFunction(validCustomOfficeCheck(_, messageType)))
        xmlParsing ~> customsOfficeSelector ~> validCustomOfficeType ~> sink

        SinkShape(xmlParsing.in)
    })

  private def validCustomOfficeCheck(maybeOffice: ParseResult[CustomsOffice], messageType: RequestMessageType): ParseResult[CustomsOffice] =
    maybeOffice.flatMap {
      office =>
        if (office.isGB || office.isXI) Right(office)
        else Left(CustomOfficeExtractorError.UnrecognisedOffice(s"Did not recognise office: ${office.value}", office, messageType.officeNode))
    }
}
