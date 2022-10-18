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

package uk.gov.hmrc.transitmovementsrouter.controllers.errors

import cats.data.EitherT
import uk.gov.hmrc.transitmovementsrouter.models.errors.HeaderExtractError
import uk.gov.hmrc.transitmovementsrouter.models.errors.PersistenceError
import uk.gov.hmrc.transitmovementsrouter.services.error.RoutingError
import uk.gov.hmrc.transitmovementsrouter.services.error.RoutingError._

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

trait ConvertError {

  implicit class FutureErrorConverter[E, A](value: EitherT[Future, E, A]) {

    def asPresentation(implicit c: Converter[E], ec: ExecutionContext): EitherT[Future, PresentationError, A] =
      value.leftMap(c.convert)
  }

  sealed trait Converter[E] {
    def convert(input: E): PresentationError
  }

  implicit val routingErrorConverter = new Converter[RoutingError] {

    def convert(routingError: RoutingError): PresentationError = routingError match {
      case Upstream(upstreamErrorResponse) => PresentationError.internalServiceError(cause = Some(upstreamErrorResponse.getCause))
      case Unexpected(_, cause)            => PresentationError.internalServiceError(cause = cause)
      case NoElementFound(element)         => PresentationError.badRequestError(s"Element $element not found")
      case TooManyElementsFound(element)   => PresentationError.badRequestError(s"Found too many elements of type $element")
      case BadDateTime(element, ex)        => PresentationError.badRequestError(s"Could not parse datetime for $element: ${ex.getMessage}")
      case UnrecognisedOffice(message)     => PresentationError.badRequestError(message)
    }

  }

  implicit val persistenceErrorConverter = new Converter[PersistenceError] {
    import uk.gov.hmrc.transitmovementsrouter.models.errors.PersistenceError._

    def convert(error: PersistenceError): PresentationError = error match {
      case MovementNotFound(movementId) => PresentationError.notFoundError(s"Movement ${movementId.value} not found")
      case Unexpected(error)            => PresentationError.internalServiceError(cause = error)
    }
  }

  implicit val headerExtractErrorConverter = new Converter[HeaderExtractError] {
    import uk.gov.hmrc.transitmovementsrouter.models.errors.HeaderExtractError._

    def convert(error: HeaderExtractError): PresentationError = error match {
      case NoHeaderFound(headerValue)       => PresentationError.badRequestError(s"Missing header: $headerValue")
      case InvalidMessageType(code: String) => PresentationError.badRequestError(s"Invalid message type: $code")
    }
  }

}
