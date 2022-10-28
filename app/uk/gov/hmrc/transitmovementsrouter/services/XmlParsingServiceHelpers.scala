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
import akka.stream.alpakka.xml.ParseEvent
import akka.stream.scaladsl.Flow
import uk.gov.hmrc.transitmovementsrouter.services.error.RoutingError

trait XmlParsingServiceHelpers {

  type ParseResult[A] = Either[RoutingError, A]

  implicit class FlowOps[A](value: Flow[ParseEvent, A, NotUsed]) {

    def single(element: String): Flow[ParseEvent, ParseResult[A], NotUsed] =
      value.fold[Either[RoutingError, A]](Left(RoutingError.NoElementFound(element)))(
        (current, next) =>
          current match {
            case Left(RoutingError.NoElementFound(_)) => Right(next)
            case _                                    => Left(RoutingError.TooManyElementsFound(element))
          }
      )

  }

}
