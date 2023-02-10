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
import akka.stream.scaladsl.FileIO
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.Source
import akka.util.ByteString
import cats.syntax.all._
import play.api.libs.Files.TemporaryFileCreator
import play.api.libs.json.Json
import play.api.libs.streams.Accumulator
import play.api.mvc.Action
import play.api.mvc.ActionBuilder
import play.api.mvc.BaseControllerHelpers
import play.api.mvc.BodyParser
import play.api.mvc.Request
import play.api.mvc.Result
import uk.gov.hmrc.transitmovementsrouter.controllers.errors.PresentationError

import java.util.concurrent.Executors
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success

trait StreamingParsers {
  self: BaseControllerHelpers =>

  implicit val materializer: Materializer

  implicit val materializerExecutionContext: ExecutionContext =
    ExecutionContext.fromExecutorService(Executors.newCachedThreadPool())

  lazy val streamFromMemory: BodyParser[Source[ByteString, _]] = BodyParser {
    _ =>
      Accumulator.source[ByteString].map(Right.apply)
  }

  implicit class ActionBuilderStreamHelpers(actionBuilder: ActionBuilder[Request, _]) {

    // This method allows for the transformation of a stream before it goes into a file,
    // such that we only transform it once.
    def stream(transformer: Flow[ByteString, ByteString, _])(
      block: Request[Source[ByteString, _]] => Future[Result]
    )(implicit temporaryFileCreator: TemporaryFileCreator): Action[Source[ByteString, _]] =
      actionBuilder.async(streamFromMemory) {
        request =>
          val tempFile = temporaryFileCreator.create()
          request.body
            .via(transformer)
            .runWith(FileIO.toPath(tempFile))
            .transformWith {
              case Success(_)   => block(request.withBody(FileIO.fromPath(tempFile)))
              case Failure(thr) => Future.successful(Status(INTERNAL_SERVER_ERROR)(Json.toJson(PresentationError.internalServiceError(cause = Some(thr)))))
            }
            .attemptTap {
              _ =>
                temporaryFileCreator.delete(tempFile)
                Future.unit
            }
      }

    def stream(
      block: Request[Source[ByteString, _]] => Future[Result]
    )(implicit temporaryFileCreator: TemporaryFileCreator): Action[Source[ByteString, _]] =
      stream(Flow.apply[ByteString])(block)(temporaryFileCreator)
  }

}
