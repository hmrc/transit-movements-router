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

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import org.mockito.Mockito.when
import org.scalatest.OptionValues
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.mockito.MockitoSugar.mock
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import play.api.Logging
import play.api.http.HeaderNames
import play.api.mvc.Action
import play.api.mvc.AnyContent
import play.api.mvc.AnyContentAsEmpty
import play.api.mvc.ControllerComponents
import play.api.test.DefaultAwaitTimeout
import play.api.test.FakeHeaders
import play.api.test.FakeRequest
import play.api.test.Helpers.contentAsString
import play.api.test.Helpers.stubControllerComponents
import uk.gov.hmrc.http.HttpVerbs.POST
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController
import uk.gov.hmrc.transitmovementsrouter.base.TestActorSystem
import uk.gov.hmrc.transitmovementsrouter.config.AppConfig
import uk.gov.hmrc.transitmovementsrouter.controllers.stream.StreamingParsers

import java.nio.charset.StandardCharsets
import scala.concurrent.Future
import scala.xml.NodeSeq

class ContentTypeRoutingSpec
    extends AnyFreeSpec
    with Matchers
    with TestActorSystem
    with ScalaCheckDrivenPropertyChecks
    with OptionValues
    with DefaultAwaitTimeout {

  class Harness(cc: ControllerComponents)(implicit val materializer: Materializer)
      extends BackendController(cc)
      with ContentTypeRouting
      with StreamingParsers
      with Logging {

    def testWithContentType: Action[Source[ByteString, ?]] = contentTypeRoute {
      case Some(_) => contentActionOne
      case None    => contentActionTwo
    }

    def contentActionOne: Action[NodeSeq] = Action.async(parse.xml) {
      _ => Future.successful(Ok("One"))
    }

    def contentActionTwo: Action[AnyContent] = Action.async {
      _ => Future.successful(Ok("Two"))
    }

    override val config: AppConfig = mock[AppConfig]
    when(config.logIncoming).thenReturn(false)
  }

  private def generateSource: Source[ByteString, NotUsed] =
    Source.fromIterator(
      () => ByteString.fromString("<test>test</test>", StandardCharsets.UTF_8).grouped(1024)
    )

  "ContentTypeRouting" - {

    "with content type set to header with body should route to contentActionOne" in {
      val cc                = stubControllerComponents()
      val sut               = new Harness(cc)
      val contentTypeHeader = FakeHeaders(Seq(HeaderNames.CONTENT_TYPE -> "application/xml"))
      val request           = FakeRequest(POST, "/", contentTypeHeader, generateSource)

      val result = sut.testWithContentType(request)
      contentAsString(result) mustBe "One"
    }

    "without content type header and body should route to contentActionTwo" in {
      val cc            = stubControllerComponents()
      val sut           = new Harness(cc)
      val withoutHeader = FakeHeaders(Seq.empty)
      val request       = FakeRequest(POST, "/", withoutHeader, AnyContentAsEmpty)

      val result = sut.testWithContentType(request)
      contentAsString(result) mustBe "Two"
    }

  }

}
