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

import cats.data.EitherT
import alleycats.std.FutureInstances
import play.api.mvc.Headers
import uk.gov.hmrc.transitmovementsrouter.models.MessageType
import uk.gov.hmrc.transitmovementsrouter.models.errors.HeaderExtractError
import uk.gov.hmrc.transitmovementsrouter.models.errors.HeaderExtractError.InvalidMessageType
import uk.gov.hmrc.transitmovementsrouter.models.errors.HeaderExtractError.NoHeaderFound

import scala.concurrent.Future

object MessageTypeHeaderExtractor extends FutureInstances {

  def extract(headers: Headers): EitherT[Future, HeaderExtractError, MessageType] =
    EitherT {
      headers.get("X-Message-Type") match {
        case None => Future.successful(Left(NoHeaderFound("X-Message-Type")))
        case Some(headerValue) =>
          MessageType.withCode(headerValue) match {
            case None              => Future.successful(Left(InvalidMessageType(s"Invalid X-Message-Type header value: $headerValue")))
            case Some(messageType) => Future.successful(Right(messageType))
          }
      }
    }
}
