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

package test.uk.gov.hmrc.transitmovementsrouter.connectors

import org.apache.pekko.stream.scaladsl.Sink
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import org.scalacheck.Gen
import org.scalatest.concurrent.PatienceConfiguration
import org.scalatest.concurrent.PatienceConfiguration.Timeout
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatest.time.SpanSugar.convertIntToGrainOfTime
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import play.api.http.Status.INTERNAL_SERVER_ERROR
import play.api.http.Status.NOT_FOUND
import play.api.http.Status.OK
import uk.gov.hmrc.transitmovementsrouter.models.errors.UpscanError.NotFound
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.test.HttpClientV2Support
import uk.gov.hmrc.transitmovementsrouter.connectors.UpscanConnectorImpl
import uk.gov.hmrc.transitmovementsrouter.it.base.TestActorSystem
import uk.gov.hmrc.transitmovementsrouter.it.base.WiremockSuite
import uk.gov.hmrc.transitmovementsrouter.models.errors.UpscanError
import uk.gov.hmrc.transitmovementsrouter.models.responses.UpscanResponse.DownloadUrl

import scala.concurrent.ExecutionContext.Implicits.global

class UpscanConnectorSpec
    extends AnyFreeSpec
    with Matchers
    with WiremockSuite
    with TestActorSystem
    with HttpClientV2Support
    with ScalaFutures
    with ScalaCheckDrivenPropertyChecks {

  implicit val timeout: PatienceConfiguration.Timeout = Timeout(3.seconds)

  "streamFile" - {

    implicit val hc: HeaderCarrier = HeaderCarrier()

    "gets a stream when the file exists" in forAll(Gen.stringOfN(20, Gen.alphaNumChar)) {
      string =>
        server.stubFor(
          get("/test.xml")
            .willReturn(aResponse().withStatus(OK).withBody(string))
        )

        val sut = new UpscanConnectorImpl(httpClientV2)
        val result = sut
          .streamFile(DownloadUrl(s"http://localhost:${server.port()}/test.xml"))
          .semiflatMap(
            stream => stream.reduce(_ ++ _).map(_.utf8String).runWith(Sink.head[String])
          )

        whenReady(result.value, timeout) {
          case Right(result) => result mustBe string
          case a             => fail(s"Expected Right($string), got $a")
        }
    }

    "returns a NotFound error when the file does not exist" in {
      server.stubFor(
        get("/test.xml")
          .willReturn(aResponse().withStatus(NOT_FOUND))
      )

      val sut = new UpscanConnectorImpl(httpClientV2)
      val result = sut
        .streamFile(DownloadUrl(s"http://localhost:${server.port()}/test.xml"))

      whenReady(result.value, timeout) {
        case Left(NotFound) => succeed
        case a              => fail(s"Expected Left(NotFound), got $a")
      }
    }

    "returns a Unexpected error when a 500 is returned" in {
      server.stubFor(
        get("/test.xml")
          .willReturn(aResponse().withStatus(INTERNAL_SERVER_ERROR))
      )

      val sut = new UpscanConnectorImpl(httpClientV2)
      val result = sut
        .streamFile(DownloadUrl(s"http://localhost:${server.port()}/test.xml"))

      whenReady(result.value, timeout) {
        case Left(UpscanError.Unexpected(_)) => succeed
        case a                               => fail(s"Expected Left(Unexpected), got $a")
      }
    }

  }

}
