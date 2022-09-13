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

package uk.gov.hmrc.transitmovementsrouter.controllers.actions

import com.google.inject.ImplementedBy
import com.google.inject.Inject
import play.api.http.HeaderNames
import play.api.libs.json.Json
import play.api.mvc.ActionFilter
import play.api.mvc.Request
import play.api.mvc.Result
import play.api.mvc.Results.BadRequest
import play.api.mvc.Results.EntityTooLarge
import uk.gov.hmrc.transitmovementsrouter.config.AppConfig
import uk.gov.hmrc.transitmovementsrouter.controllers.errors.PresentationError

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success
import scala.util.Try

@ImplementedBy(classOf[MessageSizeActionImpl[_]])
trait MessageSizeAction[R[_] <: Request[_]] extends ActionFilter[R]

class MessageSizeActionImpl[R[_] <: Request[_]] @Inject() (config: AppConfig)(implicit val executionContext: ExecutionContext) extends MessageSizeAction[R] {

  override protected def filter[A](request: R[A]): Future[Option[Result]] =
    contentLengthHeader(request) match {
      case Some((_, length)) => Future.successful(checkSize(length))
      case None =>
        Future.successful(Some(BadRequest(Json.toJson(PresentationError.badRequestError("Missing content-length header"))))) // should never happen
    }

  private def checkSize[A](length: String): Option[Result] =
    Try(length.toInt) match {
      case Success(x) if x <= limit => None
      case Success(_)               => Some(EntityTooLarge(Json.toJson(PresentationError.entityTooLargeError(s"Your message size must be less than $limit bytes"))))
      case Failure(_)               => Some(BadRequest(Json.toJson(PresentationError.badRequestError("Invalid content-length value"))))
    }

  private def contentLengthHeader[A](request: R[A]): Option[(String, String)] =
    request.headers.headers.find(_._1.equalsIgnoreCase(HeaderNames.CONTENT_LENGTH))

  private lazy val limit = config.messageSizeLimit

}
