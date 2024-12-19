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

import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Sink
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import play.api.http.HeaderNames
import play.api.libs.json.Json
import play.api.mvc.Action
import play.api.mvc.BaseController
import play.api.mvc.Request
import uk.gov.hmrc.transitmovementsrouter.controllers.errors.PresentationError
import uk.gov.hmrc.transitmovementsrouter.controllers.stream.StreamingParsers

import scala.concurrent.Future

trait ContentTypeRouting {
  self: BaseController & StreamingParsers =>

  def contentTypeRoute(routes: PartialFunction[Option[String], Action[?]])(implicit materializer: Materializer): Action[Source[ByteString, ?]] =
    Action.async(streamFromMemory) {
      (request: Request[Source[ByteString, ?]]) =>
        routes
          .lift(request.headers.get(HeaderNames.CONTENT_TYPE))
          .map(
            action => action(request).run(request.body)
          )
          .getOrElse {
            // To avoid a memory leak, we need to ensure we run the request stream and ignore it.
            request.body.to(Sink.ignore).run()
            Future.successful(
              UnsupportedMediaType(
                Json.toJson(
                  PresentationError.unsupportedMediaTypeError(
                    request.headers
                      .get(HeaderNames.CONTENT_TYPE)
                      .map(
                        headerValue => s"Content-type header $headerValue is not supported!"
                      )
                      .getOrElse("A content-type header is required!")
                  )
                )
              )
            )
          }
    }

}
