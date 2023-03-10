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

package uk.gov.hmrc.transitmovementsrouter.controllers

import cats.data.EitherT
import play.api.mvc.BaseController
import play.api.mvc.Headers
import uk.gov.hmrc.transitmovementsrouter.controllers.ObjectStoreURIExtractor.expectedUriPattern
import uk.gov.hmrc.transitmovementsrouter.controllers.errors.PresentationError
import uk.gov.hmrc.transitmovementsrouter.models.ObjectStoreResourceLocation
import uk.gov.hmrc.transitmovementsrouter.models.ObjectStoreURI
import uk.gov.hmrc.transitmovementsrouter.utils.RouterHeaderNames

import scala.concurrent.Future
import scala.util.matching.Regex

object ObjectStoreURIExtractor {

  // The URI consists of the service name in the first part of the path, followed
  // by the location of the object in the context of that service. As this service
  // targets common-transit-convention-traders' objects exclusively, we ensure
  // the URI is targeting that context. This regex ensures that this is the case.
  val expectedUriPattern: Regex = "^common-transit-convention-traders/(.+)$".r
}

trait ObjectStoreURIExtractor {
  self: BaseController =>

  def extractObjectStoreResourceLocation(objectStoreURI: ObjectStoreURI): EitherT[Future, PresentationError, ObjectStoreResourceLocation] =
    EitherT(
      Future.successful(
        for {
          objectStoreResourceLocation <- getObjectStoreResourceLocation(objectStoreURI)
        } yield ObjectStoreResourceLocation(objectStoreURI.value, objectStoreResourceLocation)
      )
    )

  def extractObjectStoreURIHeader(headers: Headers): EitherT[Future, PresentationError, ObjectStoreResourceLocation] =
    EitherT(
      Future.successful(
        for {
          headerValue                 <- getHeader(headers.get(RouterHeaderNames.OBJECT_STORE_URI))
          objectStoreResourceLocation <- getObjectStoreResourceLocation(ObjectStoreURI(headerValue))
        } yield ObjectStoreResourceLocation(headerValue, objectStoreResourceLocation)
      )
    )

  private def getHeader(objectStoreURI: Option[String]) =
    objectStoreURI.toRight(PresentationError.badRequestError("Missing X-Object-Store-Uri header value"))

  private def getObjectStoreResourceLocation(objectStoreURI: ObjectStoreURI) =
    expectedUriPattern
      .findFirstMatchIn(objectStoreURI.value)
      .map(_.group(1))
      .toRight(
        PresentationError.badRequestError(s"Object store uri value does not start with common-transit-convention-traders/ (got ${objectStoreURI.value})")
      )
}
