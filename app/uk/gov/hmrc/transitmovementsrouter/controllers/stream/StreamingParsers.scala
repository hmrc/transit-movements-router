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

import cats.implicits.catsSyntaxMonadError
import com.fasterxml.aalto.WFCException
import org.apache.pekko.stream.IOOperationIncompleteException
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.FileIO
import org.apache.pekko.stream.scaladsl.Flow
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import play.api.Logging
import play.api.libs.Files.TemporaryFileCreator
import play.api.libs.json.Json
import play.api.libs.streams.Accumulator
import play.api.mvc._
import uk.gov.hmrc.http.HeaderNames
import uk.gov.hmrc.transitmovementsrouter.config.AppConfig
import uk.gov.hmrc.transitmovementsrouter.controllers.errors.PresentationError
import uk.gov.hmrc.transitmovementsrouter.utils.RouterHeaderNames

import java.nio.file.Files
import java.util.concurrent.Executors
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Try

trait StreamingParsers {
  self: BaseControllerHelpers with Logging =>

  implicit val materializer: Materializer

  /*
    This keeps Play's connection thread pool outside of our streaming, and uses a cached thread pool
    to spin things up as needed. Additional defence against performance issues picked up in CTCP-1545.
   */
  implicit val materializerExecutionContext: ExecutionContext =
    ExecutionContext.fromExecutorService(Executors.newCachedThreadPool())

  lazy val streamFromMemory: BodyParser[Source[ByteString, _]] = BodyParser {
    _ =>
      Accumulator.source[ByteString].map(Right.apply)
  }

  val config: AppConfig

  implicit class ActionBuilderStreamHelpers(actionBuilder: ActionBuilder[Request, _]) {

    /** Updates the [[Source]] in the [[Request]] with a version that can be used
      * multiple times via the use of a temporary file.
      *
      * @param block The code to use the with the reusable source
      * @return An [[Action]]
      */

    def streamWithSize(transformer: Flow[ByteString, ByteString, _])(
      block: Request[Source[ByteString, _]] => Long => Future[Result]
    )(implicit temporaryFileCreator: TemporaryFileCreator): Action[Source[ByteString, _]] =
      actionBuilder.async(streamFromMemory) {
        request =>
          // This is outside the for comprehension because we need access to the file
          // if the rest of the futures fail, which we wouldn't get if it was in there.
          Future
            .fromTry(Try(temporaryFileCreator.create()))
            .flatMap {
              file =>
                (for {
                  _      <- request.body.via(transformer).runWith(FileIO.toPath(file))
                  size   <- Future.fromTry(Try(Files.size(file)))
                  result <- block(request.withBody(FileIO.fromPath(file)))(size)
                } yield result)
                  .attemptTap {
                    _ =>
                      file.delete()
                      Future.successful(())
                  }
            }
            .recover {
              case error: IOOperationIncompleteException if error.getCause.isInstanceOf[IllegalStateException] || error.getCause.isInstanceOf[WFCException] =>
                if (config.logIncoming) {
                  logger.error(
                    s"""Unable to process message from EIS -- bad request:
                       |
                       |Request ID: ${request.headers.get(HeaderNames.xRequestId).getOrElse("unavailable")}
                       |Correlation ID: ${request.headers.get(RouterHeaderNames.CORRELATION_ID).getOrElse("unavailable")}
                       |Conversation ID: ${request.headers.get(RouterHeaderNames.CONVERSATION_ID).getOrElse("unavailable")}
                       |Message: ${error.getMessage}
                       |
                       |Failed to transform XML""".stripMargin,
                    error
                  )
                }
                Status(BAD_REQUEST)(Json.toJson(PresentationError.badRequestError(error.getCause.getMessage)))
              case error: Throwable =>
                if (config.logIncoming) {
                  logger.error(
                    s"""Unable to process message from EIS -- internal server error:
                       |
                       |Request ID: ${request.headers.get(HeaderNames.xRequestId).getOrElse("unavailable")}
                       |Correlation ID: ${request.headers.get(RouterHeaderNames.CORRELATION_ID).getOrElse("unavailable")}
                       |Conversation ID: ${request.headers.get(RouterHeaderNames.CONVERSATION_ID).getOrElse("unavailable")}
                       |Message: ${error.getMessage}
                       |
                       |Failed to transform XML""".stripMargin,
                    error
                  )
                }
                Status(INTERNAL_SERVER_ERROR)(Json.toJson(PresentationError.internalServiceError(cause = Some(error))))
            }
      }

    def streamWithSize(
      block: Request[Source[ByteString, _]] => Long => Future[Result]
    )(implicit temporaryFileCreator: TemporaryFileCreator): Action[Source[ByteString, _]] =
      streamWithSize(Flow.apply[ByteString])(block)(temporaryFileCreator)
  }
}
