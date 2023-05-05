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
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.equalToJson
import com.github.tomakehurst.wiremock.client.WireMock.equalToXml
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
import play.api.http.HeaderNames
import play.api.http.Status.ACCEPTED
import play.api.http.Status.INTERNAL_SERVER_ERROR
import play.api.http.Status.NOT_FOUND
import play.api.libs.json.Json
import play.mvc.Http.MimeTypes
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.test.HttpClientV2Support
import uk.gov.hmrc.transitmovementsrouter.config.AppConfig
import uk.gov.hmrc.transitmovementsrouter.generators.ModelGenerators
import uk.gov.hmrc.transitmovementsrouter.it.base.TestActorSystem
import uk.gov.hmrc.transitmovementsrouter.it.base.WiremockSuite
import uk.gov.hmrc.transitmovementsrouter.models.errors.PushNotificationError.MovementNotFound
import uk.gov.hmrc.transitmovementsrouter.models.errors.PushNotificationError.Unexpected
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

  val source: Source[ByteString, _] = Source.single(ByteString.fromString("<CC029C></CC029C>"))
  val body                          = Json.obj("messageId" -> messageId.value)

  def xmlStub(codeToReturn: Int) = server.stubFor(
    post(
      urlEqualTo(uriPushNotifications)
    ).withHeader(HeaderNames.CONTENT_TYPE, equalTo(MimeTypes.XML))
      .withRequestBody(equalToXml("<CC029C></CC029C>"))
      .willReturn(aResponse().withStatus(codeToReturn))
  )

  def jsonStub(codeToReturn: Int) = server.stubFor(
    post(
      urlEqualTo(uriPushNotifications)
    ).withHeader(HeaderNames.CONTENT_TYPE, equalTo(MimeTypes.JSON))
      .withRequestBody(equalToJson(Json.obj("messageId" -> messageId.value).toString()))
      .willReturn(aResponse().withStatus(codeToReturn))
  )

  def noBodyStub(codeToReturn: Int) = server.stubFor(
    post(
      urlEqualTo(uriPushNotifications)
    )
      .willReturn(aResponse().withStatus(codeToReturn))
  )

  "post with xml body" should {
    "return unit when post is successful with body" in {
      when(mockAppConfig.pushNotificationsEnabled).thenReturn(true)

      xmlStub(ACCEPTED)

      whenReady(connector.postXML(movementId, messageId, source).value) {
        x =>
          x mustBe Right(())
      }
    }

    "return a PushNotificationError when unsuccessful with body" in forAll(errorCodes) {
      statusCode =>
        server.resetAll()

        when(mockAppConfig.pushNotificationsEnabled).thenReturn(true)

        xmlStub(statusCode)

        whenReady(connector.postXML(movementId, messageId, source).value) {
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

      xmlStub(ACCEPTED)

      val failingSource = Source.single(ByteString.fromString("{}")).via(new EISMessageTransformersImpl().unwrap)

      whenReady(connector.postXML(movementId, messageId, failingSource).value) {
        res =>
          res mustBe a[Left[Unexpected, _]]
          res.left.toOption.get.asInstanceOf[Unexpected].exception.isDefined
      }
    }

    "when the service is disabled, return a unit" in {
      when(mockAppConfig.pushNotificationsEnabled).thenReturn(false)

      xmlStub(INTERNAL_SERVER_ERROR)

      whenReady(connector.postXML(movementId, messageId, source).value) {
        case Left(_)  => fail("The stub should never have been hit and therefore should always return a right")
        case Right(_) =>
      }
    }

  }

  "post with json body" should {
    "return unit when post is successful with body" in {
      when(mockAppConfig.pushNotificationsEnabled).thenReturn(true)

      jsonStub(ACCEPTED)

      whenReady(connector.postJSON(movementId, messageId, body).value) {
        x =>
          x mustBe Right(())
      }
    }

    "return a PushNotificationError when unsuccessful with body" in forAll(errorCodes) {
      statusCode =>
        server.resetAll()

        when(mockAppConfig.pushNotificationsEnabled).thenReturn(true)

        jsonStub(statusCode)

        whenReady(connector.postJSON(movementId, messageId, body).value) {
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

  "post with no body" should {
    "successfully post a push notification with no body" in {
      when(mockAppConfig.pushNotificationsEnabled).thenReturn(true)

      noBodyStub(ACCEPTED)

      implicit val hc = HeaderCarrier()
      whenReady(connector.post(movementId, messageId).value) {
        x =>
          x mustBe Right(())
      }
    }

    "return a PushNotificationError when unsuccessful without a body" in forAll(errorCodes) {
      statusCode =>
        server.resetAll()

        when(mockAppConfig.pushNotificationsEnabled).thenReturn(true)

        noBodyStub(statusCode)

        implicit val hc = HeaderCarrier()

        whenReady(connector.post(movementId, messageId).value) {
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
