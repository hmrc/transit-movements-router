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

import akka.stream.alpakka.xml.scaladsl.XmlWriting
import akka.stream.alpakka.xml.scaladsl.XmlParsing
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.google.inject.ImplementedBy
import com.google.inject.Inject
import uk.gov.hmrc.transitmovementsrouter.models.MessageType

@ImplementedBy(classOf[StreamingMessageTrimmerImpl])
trait StreamingMessageTrimmer {

  def trim(source: Source[ByteString, _]): Source[ByteString, _]
}

class StreamingMessageTrimmerImpl @Inject() extends StreamingMessageTrimmer {

  override def trim(source: Source[ByteString, _]): Source[ByteString, _] =
    source
      .via(XmlParsing.parser)
      .via(XmlParsing.subslice("TraderChannelResponse" :: Nil))
      .via(XmlWriting.writer)
}
