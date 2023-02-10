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

import akka.stream.alpakka.xml.scaladsl.XmlParsing
import akka.stream.alpakka.xml.scaladsl.XmlWriting
import akka.stream.scaladsl.Flow
import akka.util.ByteString
import com.google.inject.ImplementedBy
import com.google.inject.Singleton

@ImplementedBy(classOf[EISMessageTransformersImpl])
trait EISMessageTransformers {

  def unwrap: Flow[ByteString, ByteString, _]

}

@Singleton
class EISMessageTransformersImpl extends EISMessageTransformers {

  // This is in line with the API 2 spec that we provided to EIS -- check that this is how they actually wrap it
  // as API 1 is different.
  lazy val unwrap: Flow[ByteString, ByteString, _] =
    Flow[ByteString]
      .via(XmlParsing.parser)
      .via(XmlParsing.subslice("TraderChannelResponse" :: Nil))
      .via(XmlWriting.writer)

}
