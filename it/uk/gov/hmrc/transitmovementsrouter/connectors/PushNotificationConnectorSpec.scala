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

import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.github.tomakehurst.wiremock.client.WireMock.{aResponse, post, urlEqualTo}
import io.lemonlabs.uri.{Url, UrlPath}
import org.mockito.Mockito.when
import org.scalacheck.Gen
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import play.api.http.Status.{INTERNAL_SERVER_ERROR, NOT_FOUND, OK}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.test.HttpClientV2Support
import uk.gov.hmrc.transitmovementsrouter.config.AppConfig
import uk.gov.hmrc.transitmovementsrouter.generators.ModelGenerators
import uk.gov.hmrc.transitmovementsrouter.it.base.{TestActorSystem, WiremockSuite}
import uk.gov.hmrc.transitmovementsrouter.models.errors.PushNotificationError.{MovementNotFound, Unexpected}
import uk.gov.hmrc.transitmovementsrouter.services.EISMessageTransformersImpl

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
    with ModelGenerators
    with TestActorSystem {

  val movementId     = arbitraryMovementId.arbitrary.sample.get
  val messageId      = arbitraryMessageId.arbitrary.sample.get


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
    "return unit when post is successful with body" in {
      when(mockAppConfig.pushNotificationsEnabled).thenReturn(true)

      stub(OK)

      whenReady(connector.post(movementId, messageId, Some(source)).value) {
        x =>
          x mustBe Right(())
      }
    }

    "return a PushNotificationError when unsuccessful with body" in forAll(errorCodes) {
      statusCode =>
        server.resetAll()

        when(mockAppConfig.pushNotificationsEnabled).thenReturn(true)

        stub(statusCode)

        whenReady(connector.post(movementId, messageId, Some(source)).value) {
          x =>
            x.isLeft mustBe true

            statusCode match {
              case NOT_FOUND             => x mustBe a[Left[MovementNotFound, _]]
              case INTERNAL_SERVER_ERROR => x mustBe a[Left[Unexpected, _]]
              case _                     => fail()
            }

        }
    }

    "return Unexpected(throwable) when NonFatal exception is thrown with body" in {
      server.resetAll()

      when(mockAppConfig.pushNotificationsEnabled).thenReturn(true)

      stub(OK)

      val failingSource = Source.single(ByteString.fromString("{}")).via(new EISMessageTransformersImpl().unwrap)

      whenReady(connector.post(movementId, messageId, Some(failingSource)).value) {
        res =>
          res mustBe a[Left[Unexpected, _]]
          res.left.toOption.get.asInstanceOf[Unexpected].exception.isDefined
      }
    }

    "when the service is disabled, return a unit" in {
      when(mockAppConfig.pushNotificationsEnabled).thenReturn(false)

      stub(INTERNAL_SERVER_ERROR)

      whenReady(connector.post(movementId, messageId, Some(source)).value) {
        case Left(_)  => fail("The stub should never have been hit and therefore should always return a right")
        case Right(_) =>
      }
    }

    "successfully post a push notification with no body" in {
      when(mockAppConfig.pushNotificationsEnabled).thenReturn(true)

      stub(OK)

      implicit val hc = HeaderCarrier()
      whenReady(connector.post(movementId, messageId, None).value) {
        x =>
          x mustBe Right(())
      }
    }

    "return a PushNotificationError when unsuccessful without a body" in forAll(errorCodes) {
      statusCode =>
        server.resetAll()

        when(mockAppConfig.pushNotificationsEnabled).thenReturn(true)

        stub(statusCode)

        implicit val hc = HeaderCarrier()

        whenReady(connector.post(movementId, messageId, None).value) {
          x =>
            x.isLeft mustBe true

            statusCode match {
              case NOT_FOUND             => x mustBe a[Left[MovementNotFound, _]]
              case INTERNAL_SERVER_ERROR => x mustBe a[Left[Unexpected, _]]
              case _                     => fail()
            }
        }
    }
  }
}
