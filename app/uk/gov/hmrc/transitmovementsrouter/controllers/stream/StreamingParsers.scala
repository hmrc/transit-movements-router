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

package uk.gov.hmrc.transitmovementsrouter.controllers.stream

import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import play.api.libs.Files.TemporaryFileCreator
import play.api.libs.streams.Accumulator
import play.api.mvc.Action
import play.api.mvc.ActionBuilder
import play.api.mvc.BaseControllerHelpers
import play.api.mvc.BodyParser
import play.api.mvc.Request
import play.api.mvc.Result
import uk.gov.hmrc.transitmovementsrouter.utils.FutureConversions
import uk.gov.hmrc.transitmovementsrouter.utils.StreamWithFile

import java.util.concurrent.Executors
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

trait StreamingParsers extends StreamWithFile with FutureConversions {
  self: BaseControllerHelpers =>

  implicit val materializer: Materializer

  // TODO: do we choose a better thread pool, or make configurable?
  //  We have to be careful to not use Play's EC because we could accidentally starve the thread pool
  //  and cause errors for additional connections
  implicit val materializerExecutionContext: ExecutionContext =
    ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(4))

  lazy val streamFromMemory: BodyParser[Source[ByteString, _]] = BodyParser {
    _ =>
      Accumulator.source[ByteString].map(Right.apply)
  }

  implicit class ActionBuilderStreamHelpers(actionBuilder: ActionBuilder[Request, _]) {

    def stream(
      block: Request[Source[ByteString, _]] => Future[Result]
    )(implicit temporaryFileCreator: TemporaryFileCreator): Action[Source[ByteString, _]] =
      actionBuilder.async(streamFromMemory) {
        request =>
          withReusableSource(request.body) {
            memoryOrFileSource =>
              block(request.withBody(memoryOrFileSource))
          }
      }
  }

}
