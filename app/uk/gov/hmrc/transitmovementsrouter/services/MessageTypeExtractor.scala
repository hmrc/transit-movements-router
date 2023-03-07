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

import akka.stream.Materializer
import akka.stream.alpakka.xml.StartElement
import akka.stream.alpakka.xml.scaladsl.XmlParsing
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.Keep
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.util.ByteString
import cats.data.EitherT
import com.fasterxml.aalto.WFCException
import com.google.inject.ImplementedBy
import com.google.inject.Inject
import com.google.inject.Singleton
import play.api.mvc.Headers
import uk.gov.hmrc.transitmovementsrouter.models.MessageType
import uk.gov.hmrc.transitmovementsrouter.models.errors.MessageTypeExtractionError
import uk.gov.hmrc.transitmovementsrouter.models.errors.MessageTypeExtractionError.InvalidMessageType
import uk.gov.hmrc.transitmovementsrouter.models.errors.MessageTypeExtractionError.UnableToExtractFromBody
import uk.gov.hmrc.transitmovementsrouter.models.errors.MessageTypeExtractionError.UnableToExtractFromHeader
import uk.gov.hmrc.transitmovementsrouter.utils.RouterHeaderNames

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.control.NonFatal

@ImplementedBy(classOf[MessageTypeExtractorImpl])
trait MessageTypeExtractor {

  def extract(headers: Headers, source: Source[ByteString, _]): EitherT[Future, MessageTypeExtractionError, MessageType]

  def extractFromHeaders(headers: Headers): EitherT[Future, MessageTypeExtractionError, MessageType]

  def extractFromBody(source: Source[ByteString, _]): EitherT[Future, MessageTypeExtractionError, MessageType]

}

@Singleton
class MessageTypeExtractorImpl @Inject() (implicit ec: ExecutionContext, mat: Materializer) extends MessageTypeExtractor {

  type MaybeMessageType = Either[MessageTypeExtractionError, MessageType]

  lazy val messageTypeExtractor: Sink[ByteString, Future[MaybeMessageType]] = Flow[ByteString]
    .via(XmlParsing.parser)
    .filter {
      case _: StartElement => true
      case _               => false
    }
    .take(1)
    .map {
      case s: StartElement =>
        MessageType.values
          .find(_.rootNode == s.localName)
          .map(Right(_))
          .getOrElse(Left(InvalidMessageType(s.localName)))
      case _ => Left(UnableToExtractFromBody)
    }
    .recover {
      case _: WFCException => Left(UnableToExtractFromBody)
      case NonFatal(e)     => Left(MessageTypeExtractionError.Unexpected(Some(e)))
    }
    .fold[MaybeMessageType](Left(UnableToExtractFromBody))(
      (_, incoming) => incoming
    )
    .toMat(Sink.head)(Keep.right)

  override def extract(headers: Headers, source: Source[ByteString, _]): EitherT[Future, MessageTypeExtractionError, MessageType] =
    extractFromHeaders(headers).leftFlatMap(
      _ => extractFromBody(source)
    )

  override def extractFromHeaders(headers: Headers): EitherT[Future, MessageTypeExtractionError, MessageType] =
    EitherT {
      headers.get(RouterHeaderNames.MESSAGE_TYPE) match {
        case None => Future.successful(Left(UnableToExtractFromHeader))
        case Some(headerValue) =>
          MessageType.withCode(headerValue) match {
            case None              => Future.successful(Left(InvalidMessageType(headerValue)))
            case Some(messageType) => Future.successful(Right(messageType))
          }
      }
    }

  override def extractFromBody(source: Source[ByteString, _]): EitherT[Future, MessageTypeExtractionError, MessageType] =
    EitherT(source.runWith(messageTypeExtractor))
}
