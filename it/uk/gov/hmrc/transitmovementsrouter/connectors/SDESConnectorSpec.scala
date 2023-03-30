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

package uk.gov.hmrc.transitmovementsrouter.connectors

import com.github.tomakehurst.wiremock.client.WireMock._
import org.scalacheck.Arbitrary.arbitrary
import org.scalatest.concurrent.IntegrationPatience
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import play.api.http.HeaderNames
import play.api.http.Status.INTERNAL_SERVER_ERROR
import play.api.http.Status.NO_CONTENT
import play.api.libs.json.JsSuccess
import play.api.libs.json.Json
import play.mvc.Http.MimeTypes
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.UpstreamErrorResponse
import uk.gov.hmrc.http.test.HttpClientV2Support
import uk.gov.hmrc.transitmovementsrouter.config.AppConfig
import uk.gov.hmrc.transitmovementsrouter.controllers.errors.PresentationError
import uk.gov.hmrc.transitmovementsrouter.controllers.errors.StandardError
import uk.gov.hmrc.transitmovementsrouter.generators.ModelGenerators
import uk.gov.hmrc.transitmovementsrouter.it.base.WiremockSuiteWithGuice

import uk.gov.hmrc.transitmovementsrouter.models.errors.ErrorCode
import uk.gov.hmrc.transitmovementsrouter.models.sdes._

import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal

class SDESConnectorSpec
    extends AnyFreeSpec
    with HttpClientV2Support
    with Matchers
    with GuiceOneAppPerSuite
    with WiremockSuiteWithGuice
    with ScalaFutures
    with MockitoSugar
    with IntegrationPatience
    with ModelGenerators
    with ScalaCheckDrivenPropertyChecks {

  lazy val appConfig: AppConfig = app.injector.instanceOf[AppConfig]

  implicit lazy val ec: ExecutionContext = app.materializer.executionContext

  implicit val hc: HeaderCarrier = HeaderCarrier()

  lazy val sdesConnector: SDESConnector = new SDESConnectorImpl(httpClientV2, appConfig)

  lazy val url = "/sdes-stub/notification/fileready"

  "POST /notification/fileready" - {

    "When SDES responds with NO_CONTENT, must returned a successful future" in forAll(
      arbitrary[SdesFilereadyRequest]
    ) {
      request =>
        server.resetAll()

        server.stubFor(
          post(
            urlEqualTo(url)
          ).withRequestBody(equalToJson(Json.stringify(Json.toJson(request))))
            .withHeader(HeaderNames.CONTENT_TYPE, equalTo(MimeTypes.JSON))
            .withHeader("X-Client-Id", equalTo("CTC"))
            .willReturn(aResponse().withStatus(NO_CONTENT))
        )

        whenReady(
          sdesConnector
            .send(request)
        ) {
          result =>
            result mustBe ()
        }
    }

    "On an upstream internal server error, get a failed Future with an UpstreamErrorResponse" in forAll(
      arbitrary[SdesFilereadyRequest]
    ) {
      request =>
        server.resetAll()

        server.stubFor(
          post(
            urlEqualTo(url)
          ).withRequestBody(equalToJson(Json.stringify(Json.toJson(request))))
            .withHeader(HeaderNames.CONTENT_TYPE, equalTo(MimeTypes.JSON))
            .withHeader("X-Client-Id", equalTo("CTC"))
            .willReturn(
              aResponse()
                .withStatus(INTERNAL_SERVER_ERROR)
                .withBody(
                  Json.stringify(Json.toJson(PresentationError.internalServiceError()))
                )
            )
        )

        val future = sdesConnector.send(request).map(Right(_)).recover {
          case NonFatal(e) => Left(e)
        }

        whenReady(future) {
          result =>
            result.left.toOption.get mustBe a[UpstreamErrorResponse]
            val response = result.left.toOption.get.asInstanceOf[UpstreamErrorResponse]
            response.statusCode mustBe INTERNAL_SERVER_ERROR
            Json.parse(response.message).validate[StandardError] mustBe JsSuccess(StandardError("Internal server error", ErrorCode.InternalServerError))
        }
    }

  }

}
