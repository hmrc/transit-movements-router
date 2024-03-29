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

package uk.gov.hmrc.transitmovementsrouter.models.errors

import uk.gov.hmrc.http.UpstreamErrorResponse
import uk.gov.hmrc.transitmovementsrouter.models.LocalReferenceNumber

import java.time.format.DateTimeParseException

object RoutingError {
  case class Upstream(upstreamErrorResponse: UpstreamErrorResponse)                         extends RoutingError
  case class Unexpected(message: String, cause: Option[Throwable])                          extends RoutingError
  case class BadDateTime(element: String, exception: DateTimeParseException)                extends RoutingError
  case class DuplicateLRNError(message: String, code: ErrorCode, lrn: LocalReferenceNumber) extends RoutingError
}

sealed trait RoutingError
