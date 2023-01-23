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

import org.mockito.Mockito.when
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.mockito.MockitoSugar
import play.api.http.HeaderNames
import play.api.http.HttpVerbs
import play.api.http.Status.BAD_REQUEST
import play.api.http.Status.OK
import play.api.http.Status.REQUEST_ENTITY_TOO_LARGE
import play.api.mvc.Request
import play.api.mvc.Results.Ok
import play.api.test.FakeHeaders
import play.api.test.FakeRequest
import play.api.test.Helpers.contentAsJson
import play.api.test.Helpers.defaultAwaitTimeout
import play.api.test.Helpers.status
import uk.gov.hmrc.transitmovementsrouter.config.AppConfig

import scala.concurrent.Future
import scala.xml.NodeSeq
import scala.concurrent.ExecutionContext.Implicits.global

class MessageSizeActionSpec extends AnyFreeSpec with Matchers with ScalaFutures with MockitoSugar {

  val appConfig: AppConfig = mock[AppConfig]
  when(appConfig.messageSizeLimit).thenReturn(500000)

  def sut() = new MessageSizeActionImpl[Request](appConfig)

  val testXml = <test></test>

  "MessageSizeAction " - {

    "must allow a POST under 0.5mb" in {

      val req: FakeRequest[NodeSeq] =
        FakeRequest(method = HttpVerbs.POST, uri = "", headers = FakeHeaders(Seq(HeaderNames.CONTENT_LENGTH -> "123")), testXml)
      val result = sut().invokeBlock(req, (_: Request[_]) => Future.successful(Ok))

      status(result) mustEqual OK
    }

    "must reject a POST over 0.5mb" in {
      val req: FakeRequest[NodeSeq] =
        FakeRequest(method = HttpVerbs.POST, uri = "", headers = FakeHeaders(Seq(HeaderNames.CONTENT_LENGTH -> "500001")), testXml)
      val result = sut().invokeBlock(req, (_: Request[_]) => Future.successful(Ok))

      status(result) mustEqual REQUEST_ENTITY_TOO_LARGE

      val jsonResult = contentAsJson(result)
      (jsonResult \ "message").as[String] matches "Your message size must be less than [0-9]* bytes" mustBe true
      (jsonResult \ "code").as[String] mustEqual "REQUEST_ENTITY_TOO_LARGE"
    }

    "must reject a PUT over 0.5mb" in {

      val req: FakeRequest[NodeSeq] =
        FakeRequest(method = HttpVerbs.PUT, uri = "", headers = FakeHeaders(Seq(HeaderNames.CONTENT_LENGTH -> "500001")), testXml)
      val result = sut().invokeBlock(req, (_: Request[_]) => Future.successful(Ok))

      status(result) mustEqual REQUEST_ENTITY_TOO_LARGE
    }

    "must NOT allow a POST if content-length header is missing" in {

      val req: FakeRequest[NodeSeq] =
        FakeRequest(method = HttpVerbs.POST, uri = "", headers = FakeHeaders(Seq()), testXml)
      val result = sut().invokeBlock(req, (_: Request[_]) => Future.successful(Ok))

      status(result) mustEqual BAD_REQUEST
      val jsonResult = contentAsJson(result)
      (jsonResult \ "message").as[String] mustEqual "Missing content-length header"
      (jsonResult \ "code").as[String] mustEqual "BAD_REQUEST"
    }

    "must NOT allow a POST if content-length header is invalid" in {

      val req: FakeRequest[NodeSeq] = FakeRequest(method = HttpVerbs.POST, uri = "", headers = FakeHeaders(Seq(HeaderNames.CONTENT_LENGTH -> "ABC")), testXml)
      val result                    = sut().invokeBlock(req, (_: Request[_]) => Future.successful(Ok))

      status(result) mustEqual BAD_REQUEST
      val jsonResult = contentAsJson(result)
      (jsonResult \ "message").as[String] mustEqual "Invalid content-length value"
      (jsonResult \ "code").as[String] mustEqual "BAD_REQUEST"
    }
  }
}
