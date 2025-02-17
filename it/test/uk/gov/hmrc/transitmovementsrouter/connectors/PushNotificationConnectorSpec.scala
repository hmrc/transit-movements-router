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

import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import com.github.tomakehurst.wiremock.client.WireMock.{aResponse, equalTo, equalToJson, equalToXml, getRequestedFor, post, urlEqualTo}
import com.github.tomakehurst.wiremock.stubbing.StubMapping
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
import play.api.libs.json.JsObject
import play.api.libs.json.Json
import play.mvc.Http.MimeTypes
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.test.HttpClientV2Support
import uk.gov.hmrc.transitmovementsrouter.config.AppConfig
import uk.gov.hmrc.transitmovementsrouter.connectors.PushNotificationsConnectorImpl
import uk.gov.hmrc.transitmovementsrouter.it.base.TestActorSystem
import uk.gov.hmrc.transitmovementsrouter.it.base.WiremockSuite
import uk.gov.hmrc.transitmovementsrouter.it.generators.ModelGenerators
import uk.gov.hmrc.transitmovementsrouter.models.{EoriNumber, MessageId, MessageType, MovementId, PersistenceResponse}
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

  val movementId: MovementId   = arbitraryMovementId.arbitrary.sample.get
  val messageId: MessageId     = arbitraryMessageId.arbitrary.sample.get
  val messageType: MessageType = arbitraryMessageType.arbitrary.sample.get
  val eoriNumber: EoriNumber = arbitraryEoriNumber.arbitrary.sample.get

  val persistenceResponse: PersistenceResponse = PersistenceResponse(messageId, eoriNumber, None, false, None)

  val messageReceivedUri: String =
    UrlPath.parse(s"/transit-movements-push-notifications/traders/movements/${movementId.value}/messages/${messageId.value}/messageReceived").toString()

  val submissionNotificationUri: String =
    UrlPath.parse(s"/transit-movements-push-notifications/traders/movements/${movementId.value}/messages/${messageId.value}/submissionNotification").toString()

  val token: String = Gen.alphaNumStr.sample.get

  implicit val mockAppConfig: AppConfig = mock[AppConfig]
  when(mockAppConfig.internalAuthToken).thenReturn(token)
  when(mockAppConfig.transitMovementsPushNotificationsUrl).thenAnswer {
    _ =>
      Url.parse(server.baseUrl())
  }

  implicit val hc: HeaderCarrier = HeaderCarrier()

  lazy val connector = new PushNotificationsConnectorImpl(httpClientV2)

  val errorCodes: Gen[Int] = Gen.oneOf(
    Seq(
      INTERNAL_SERVER_ERROR,
      NOT_FOUND
    )
  )

  val source: Source[ByteString, ?] = Source.single(ByteString.fromString("<CC029C></CC029C>"))
  val body: JsObject                = Json.obj("messageId" -> messageId.value)

  def xmlStub(codeToReturn: Int): StubMapping = server.stubFor(
    post(
      urlEqualTo(messageReceivedUri)
    ).withHeader(HeaderNames.AUTHORIZATION, equalTo(token))
      .withHeader(HeaderNames.CONTENT_TYPE, equalTo(MimeTypes.XML))
      .withHeader("X-Message-Type", equalTo(messageType.code))
      .withRequestBody(equalToXml("<CC029C></CC029C>"))
      .willReturn(aResponse().withStatus(codeToReturn))
  )

  def verifyZeroInteraction = server.verify(0, getRequestedFor(urlEqualTo(messageReceivedUri)))

  def jsonStub(codeToReturn: Int): StubMapping = server.stubFor(
    post(
      urlEqualTo(submissionNotificationUri)
    ).withHeader(HeaderNames.AUTHORIZATION, equalTo(token))
      .withHeader(HeaderNames.CONTENT_TYPE, equalTo(MimeTypes.JSON))
      .withRequestBody(equalToJson(Json.obj("messageId" -> messageId.value).toString()))
      .willReturn(aResponse().withStatus(codeToReturn))
  )

  def noBodyStub(codeToReturn: Int): StubMapping = server.stubFor(
    post(
      urlEqualTo(messageReceivedUri)
    )
      .withHeader(HeaderNames.AUTHORIZATION, equalTo(token))
      .withHeader("X-Message-Type", equalTo(messageType.code))
      .willReturn(aResponse().withStatus(codeToReturn))
  )

  "POST messageReceived notification with body" should {
    "return unit when post is successful with body" in {
      when(mockAppConfig.pushNotificationsEnabled).thenReturn(true)

      xmlStub(ACCEPTED)

      whenReady(connector.postMessageReceived(movementId, persistenceResponse, messageType, source).value) {
        x =>
          x mustBe Right(())
      }
    }

    "return unit when post is successful with body when 'sendNotification is true" in {
      when(mockAppConfig.pushNotificationsEnabled).thenReturn(true)

      xmlStub(ACCEPTED)

      whenReady(connector.postMessageReceived(movementId, persistenceResponse.copy(sendNotification = Some(true)), messageType, source).value) {
        x =>
          x mustBe Right(())
      }
    }

    "return unit when post is successful with body when 'sendNotification is None" in {
      when(mockAppConfig.pushNotificationsEnabled).thenReturn(true)

      xmlStub(ACCEPTED)

      whenReady(connector.postMessageReceived(movementId, persistenceResponse.copy(sendNotification = None), messageType, source).value) {
        x =>
          x mustBe Right(())
      }
    }

    "return unit and do not make a request to PPNS when 'sendNotifiation' is false" in {
      when(mockAppConfig.pushNotificationsEnabled).thenReturn(true)

      whenReady(connector.postMessageReceived(movementId, persistenceResponse.copy(sendNotification = Some(false)), messageType, source).value) {
        x =>
          x mustBe Right(())
      }

      verifyZeroInteraction
    }

    "return a PushNotificationError when unsuccessful with body" in forAll(errorCodes) {
      statusCode =>
        server.resetAll()

        when(mockAppConfig.pushNotificationsEnabled).thenReturn(true)



        xmlStub(statusCode)

        whenReady(connector.postMessageReceived(movementId, persistenceResponse, messageType, source).value) {
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

      whenReady(connector.postMessageReceived(movementId, persistenceResponse, messageType, failingSource).value) {
        res =>
          res mustBe a[Left[Unexpected, _]]
          res.left.toOption.get.asInstanceOf[Unexpected].exception.isDefined
      }
    }

    "when the service is disabled, return a unit" in {
      when(mockAppConfig.pushNotificationsEnabled).thenReturn(false)

      xmlStub(INTERNAL_SERVER_ERROR)

      whenReady(connector.postMessageReceived(movementId, persistenceResponse, messageType, source).value) {
        case Left(_)  => fail("The stub should never have been hit and therefore should always return a right")
        case Right(_) =>
      }
    }

  }

  "POST submissionNotification with body" should {
    "return unit when post is successful with body" in {
      when(mockAppConfig.pushNotificationsEnabled).thenReturn(true)

      jsonStub(ACCEPTED)

      whenReady(connector.postSubmissionNotification(movementId, messageId, body).value) {
        x =>
          x mustBe Right(())
      }
    }

    "return a PushNotificationError when unsuccessful with body" in forAll(errorCodes) {
      statusCode =>
        server.resetAll()

        when(mockAppConfig.pushNotificationsEnabled).thenReturn(true)

        jsonStub(statusCode)

        whenReady(connector.postSubmissionNotification(movementId, messageId, body).value) {
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

  "POST messageReceived notification without body" should {
    "successfully post a push notification with no body" in {
      when(mockAppConfig.pushNotificationsEnabled).thenReturn(true)

      noBodyStub(ACCEPTED)

      implicit val hc: HeaderCarrier = HeaderCarrier()
      whenReady(connector.postMessageReceived(movementId, messageId, messageType).value) {
        x =>
          x mustBe Right(())
      }
    }

    "return a PushNotificationError when unsuccessful without a body" in forAll(errorCodes) {
      statusCode =>
        server.resetAll()

        when(mockAppConfig.pushNotificationsEnabled).thenReturn(true)

        noBodyStub(statusCode)

        implicit val hc: HeaderCarrier = HeaderCarrier()

        whenReady(connector.postMessageReceived(movementId, messageId, messageType).value) {
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
