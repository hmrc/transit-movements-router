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

package it.uk.gov.hmrc.transitmovementsrouter.connectors

import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import io.lemonlabs.uri.Url
import io.lemonlabs.uri.UrlPath
import org.mockito.Mockito.when
import org.scalacheck.Gen
import org.scalatest.concurrent.IntegrationPatience
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import play.api.http.Status.INTERNAL_SERVER_ERROR
import play.api.http.Status.NOT_FOUND
import play.api.http.Status.OK
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.test.HttpClientV2Support
import uk.gov.hmrc.transitmovementsrouter.config.AppConfig
import uk.gov.hmrc.transitmovementsrouter.generators.ModelGenerators
import uk.gov.hmrc.transitmovementsrouter.models.errors.PushNotificationError.MovementNotFound
import uk.gov.hmrc.transitmovementsrouter.models.errors.PushNotificationError.Unexpected
import uk.gov.hmrc.transitmovementsrouter.services.StreamingMessageTrimmerImpl

import scala.concurrent.ExecutionContext.Implicits.global

class PushNotificationConnectorSpec
    extends AnyWordSpec
    with HttpClientV2Support
    with Matchers
    with WiremockSuite
    with ScalaFutures
    with MockitoSugar
    with IntegrationPatience
    with ScalaCheckPropertyChecks
    with ModelGenerators {

  val movementId = arbitraryMovementId.arbitrary.sample.get
  val messageId  = arbitraryMessageId.arbitrary.sample.get

  val uriPushNotifications =
    UrlPath.parse(s"/transit-movements-push-notifications/traders/movements/${movementId.value}/messages/${messageId.value}").toString()

  val mockAppConfig = mock[AppConfig]

  when(mockAppConfig.transitMovementsPushNotificationsUrl).thenAnswer {
    _ =>
      Url.parse(server.baseUrl())
  }

  implicit val hc = HeaderCarrier()

  lazy val connector = new PushNotificationsConnectorImpl(httpClientV2, mockAppConfig)

  val errorCodes = Gen.oneOf(
    Seq(
      INTERNAL_SERVER_ERROR,
      NOT_FOUND
    )
  )

  def source: Source[ByteString, _] = Source.single(ByteString.fromString("<CC029C></CC029C>"))

  def stub(codeToReturn: Int) = server.stubFor(
    post(
      urlEqualTo(uriPushNotifications)
    ).willReturn(aResponse().withStatus(codeToReturn))
  )

  "post" should {
    "return unit when post is successful" in {
      when(mockAppConfig.pushNotificationsEnabled).thenReturn(true)

      stub(OK)

      whenReady(connector.post(movementId, messageId, source).value) {
        x =>
          x mustBe Right(())
      }
    }

    "return a PushNotificationError when unsuccessful" in forAll(errorCodes) {
      statusCode =>
        server.resetAll()

        when(mockAppConfig.pushNotificationsEnabled).thenReturn(true)

        stub(statusCode)

        whenReady(connector.post(movementId, messageId, source).value) {
          x =>
            x.isLeft mustBe true

            statusCode match {
              case NOT_FOUND             => x mustBe a[Left[MovementNotFound, _]]
              case INTERNAL_SERVER_ERROR => x mustBe a[Left[Unexpected, _]]
            }

        }
    }

    "return Unexpected(throwable) when NonFatal exception is thrown" in {
      server.resetAll()

      when(mockAppConfig.pushNotificationsEnabled).thenReturn(true)

      stub(OK)

      val failingSource = new StreamingMessageTrimmerImpl().trim(Source.single(ByteString.fromString("{}")))

      whenReady(connector.post(movementId, messageId, failingSource).value) {
        res =>
          res mustBe a[Left[Unexpected, _]]
          res.left.toOption.get.asInstanceOf[Unexpected].exception.isDefined
      }
    }

    "when the service is disabled, return a unit" in {
      when(mockAppConfig.pushNotificationsEnabled).thenReturn(false)

      stub(INTERNAL_SERVER_ERROR)

      whenReady(connector.post(movementId, messageId, source).value) {
        case Left(_)  => fail("The stub should never have been hit and therefore should always return a right")
        case Right(_) =>
      }

    }
  }
}
