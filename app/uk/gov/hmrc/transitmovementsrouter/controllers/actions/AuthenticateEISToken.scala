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

package uk.gov.hmrc.transitmovementsrouter.controllers.actions

import com.google.inject.ImplementedBy
import com.google.inject.Inject
import com.google.inject.Singleton
import play.api.http.Status.UNAUTHORIZED
import play.api.libs.json.Json
import play.api.mvc.ActionBuilder
import play.api.mvc.ActionFilter
import play.api.mvc.AnyContent
import play.api.mvc.BodyParser
import play.api.mvc.BodyParsers
import play.api.mvc.Headers
import play.api.mvc.Request
import play.api.mvc.Result
import play.api.mvc.Results.Status
import uk.gov.hmrc.transitmovementsrouter.config.AppConfig
import uk.gov.hmrc.transitmovementsrouter.controllers.errors.PresentationError

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

@ImplementedBy(classOf[AuthenticateEISTokenImpl])
trait AuthenticateEISToken extends ActionFilter[Request] with ActionBuilder[Request, AnyContent]

@Singleton
class AuthenticateEISTokenImpl @Inject() (appConfig: AppConfig, parsers: BodyParsers.Default)(implicit val executionContext: ExecutionContext)
    extends AuthenticateEISToken {

  private val incomingAuthConfig = appConfig.incomingAuth
  private val tokenPattern       = "^Bearer (.+)$".r

  override def filter[A](request: Request[A]): Future[Option[Result]] =
    if (incomingAuthConfig.enabled) {
      (for {
        authVal    <- request.headers.get("Authorization")
        tokenMatch <- tokenPattern.findFirstMatchIn(authVal)
        token = tokenMatch.group(1)
        if incomingAuthConfig.acceptedTokens.contains(token)
      } yield token) match {
        case Some(t) => Future.successful(None)
        case None    => Future.successful(Some(createUnauthorisedResponse(request.headers)))
      }

    } else Future.successful(None)

  private def createUnauthorisedResponse(headers: Headers): Result =
    Status(UNAUTHORIZED)(Json.toJson(PresentationError.unauthorisedError("Supplied Bearer token is invalid")))
      .withHeaders(headers.headers.filter(_._1.toLowerCase == "x-correlation-id"): _*)

  override def parser: BodyParser[AnyContent] = parsers
}
