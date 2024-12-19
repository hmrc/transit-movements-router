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

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Flow
import org.apache.pekko.stream.scaladsl.Sink
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import org.mockito.Mockito.when
import org.scalacheck.Gen
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.mockito.MockitoSugar.mock
import play.api.Logging
import play.api.http.HeaderNames
import play.api.http.Status.BAD_REQUEST
import play.api.http.Status.INTERNAL_SERVER_ERROR
import play.api.http.Status.OK
import play.api.libs.Files
import play.api.libs.Files.SingletonTemporaryFileCreator
import play.api.libs.json.Json
import play.api.mvc.Action
import play.api.mvc.ActionBuilder
import play.api.mvc.ActionRefiner
import play.api.mvc.AnyContent
import play.api.mvc.BaseController
import play.api.mvc.BodyParser
import play.api.mvc.ControllerComponents
import play.api.mvc.Request
import play.api.mvc.Result
import play.api.test.FakeHeaders
import play.api.test.FakeRequest
import play.api.test.Helpers.contentAsJson
import play.api.test.Helpers.contentAsString
import play.api.test.Helpers.defaultAwaitTimeout
import play.api.test.Helpers.status
import play.api.test.Helpers.stubControllerComponents
import uk.gov.hmrc.transitmovementsrouter.base.TestActorSystem
import uk.gov.hmrc.transitmovementsrouter.base.TestSourceProvider
import uk.gov.hmrc.transitmovementsrouter.config.AppConfig
import uk.gov.hmrc.transitmovementsrouter.controllers.errors.PresentationError

import java.nio.charset.StandardCharsets
import scala.annotation.tailrec
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

class StreamingParsersSpec extends AnyFreeSpec with Matchers with TestActorSystem with TestSourceProvider {

  lazy val headers: FakeHeaders = FakeHeaders(Seq(HeaderNames.CONTENT_TYPE -> "text/plain", HeaderNames.ACCEPT -> "application/vnd.hmrc.2.0+json"))

  object TestActionBuilder extends ActionRefiner[Request, Request] with ActionBuilder[Request, AnyContent] {

    override protected def refine[A](request: Request[A]): Future[Either[Result, Request[A]]] =
      Future.successful(Right(request.withBody(request.body)))

    override protected def executionContext: ExecutionContext = scala.concurrent.ExecutionContext.global

    override def parser: BodyParser[AnyContent] = stubControllerComponents().parsers.defaultBodyParser
  }

  object Harness extends BaseController with StreamingParsers with Logging {

    override val controllerComponents: ControllerComponents                     = stubControllerComponents()
    implicit val temporaryFileCreator: Files.SingletonTemporaryFileCreator.type = SingletonTemporaryFileCreator
    implicit val materializer: Materializer                                     = Materializer(TestActorSystem.system)

    def testFromMemory: Action[Source[ByteString, ?]] = Action.async(streamFromMemory) {
      request => result.apply(request).run(request.body)(materializer)
    }

    def result: Action[String] = Action.async(parse.text) {
      request =>
        Future.successful(Ok(request.body))
    }

    def resultStream: Action[Source[ByteString, ?]] = Action.andThen(TestActionBuilder).streamWithSize {
      request => _ =>
        (for {
          a <- request.body.runWith(Sink.last)
          b <- request.body.runWith(Sink.last)
        } yield (a ++ b).utf8String)
          .map(
            r => Ok(r)
          )
    }

    def transformStream: Action[Source[ByteString, ?]] = Action
      .andThen(TestActionBuilder)
      .streamWithSize(
        Flow.fromFunction[ByteString, ByteString](
          a => a ++ a
        )
      ) {
        request => _ =>
          (for {
            a <- request.body.runWith(Sink.last)
            b <- request.body.runWith(Sink.last)
          } yield (a ++ b).utf8String)
            .map(
              r => Ok(r)
            )
      }

    def errorStream: Action[Source[ByteString, ?]] = Action
      .andThen(TestActionBuilder)
      .streamWithSize(
        Flow.fromFunction[ByteString, ByteString](
          _ => throw new IllegalStateException("this happened")
        )
      ) {
        _ => _ =>
          Future.successful(Ok("but should never happen"))
      }

    def internalErrorStream: Action[Source[ByteString, ?]] = Action
      .andThen(TestActionBuilder)
      .streamWithSize(
        Flow.fromFunction[ByteString, ByteString](
          _ => throw new NumberFormatException() // doesn't matter - we're just not expecting it
        )
      ) {
        _ => _ =>
          Future.successful(Ok("but should never happen"))
      }

    override val config: AppConfig = mock[AppConfig]
    when(config.logIncoming).thenReturn(false)
  }

  @tailrec
  private def generateByteString(kb: Int, accumulator: ByteString = ByteString.empty): ByteString =
    if (kb <= 0) accumulator
    else {
      lazy val valueAsByte: Byte = (kb % 10).toString.getBytes(StandardCharsets.UTF_8)(0) // one byte each
      generateByteString(kb - 1, ByteString.fromArray(Array.fill(1024)(valueAsByte)) ++ accumulator)
    }

  private def generateSource(byteString: ByteString): Source[ByteString, NotUsed] =
    Source(byteString.grouped(1024).toSeq)

  "Streaming" - {
    "from Memory" - {
      (1 to 5).foreach {
        value =>
          s"~$value kb string is created" in {
            val byteString = generateByteString(value)
            val request    = FakeRequest("POST", "/", headers, generateSource(byteString))
            val result     = Harness.testFromMemory()(request)
            status(result) mustBe OK
            contentAsString(result) mustBe byteString.utf8String
          }
      }
    }

    "via the stream extension method" in {
      val string  = Gen.stringOfN(20, Gen.alphaNumChar).sample.get
      val request = FakeRequest("POST", "/", headers, singleUseStringSource(string))
      val result  = Harness.resultStream()(request)
      status(result) mustBe OK
      contentAsString(result) mustBe (string ++ string)
    }

    "via the stream extension method with a transformation" in {
      val string  = Gen.stringOfN(20, Gen.alphaNumChar).sample.get
      val request = FakeRequest("POST", "/", headers, singleUseStringSource(string))
      val result  = Harness.transformStream()(request)
      status(result) mustBe OK
      contentAsString(result) mustBe (string ++ string ++ string ++ string)
    }

    "via the stream extension method with a transformation that fails in an expected way" in {
      val string  = Gen.stringOfN(20, Gen.alphaNumChar).sample.get
      val request = FakeRequest("POST", "/", headers, singleUseStringSource(string))
      val result  = Harness.errorStream()(request)
      status(result) mustBe BAD_REQUEST
      contentAsJson(result) mustBe Json.toJson(PresentationError.badRequestError("this happened"))
    }

    "via the stream extension method with a transformation that fails in an unexpected way" in {
      val string  = Gen.stringOfN(20, Gen.alphaNumChar).sample.get
      val request = FakeRequest("POST", "/", headers, singleUseStringSource(string))
      val result  = Harness.internalErrorStream()(request)
      status(result) mustBe INTERNAL_SERVER_ERROR
      contentAsJson(result) mustBe Json.toJson(PresentationError.internalServiceError())
    }
  }
}
