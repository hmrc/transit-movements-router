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

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.equalToJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import org.mockito.Mockito.when
import org.scalacheck.Arbitrary.arbitrary
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatest.time.SpanSugar.convertIntToGrainOfTime
import org.scalatestplus.mockito.MockitoSugar.mock
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.UpstreamErrorResponse
import uk.gov.hmrc.http.test.HttpClientV2Support
import uk.gov.hmrc.transitmovementsrouter.config.AppConfig
import uk.gov.hmrc.transitmovementsrouter.connectors.ServiceMonitoringConnectorImpl
import uk.gov.hmrc.transitmovementsrouter.it.base.TestActorSystem
import uk.gov.hmrc.transitmovementsrouter.it.base.WiremockSuite
import uk.gov.hmrc.transitmovementsrouter.it.generators.ModelGenerators
import uk.gov.hmrc.transitmovementsrouter.models.ConversationId
import uk.gov.hmrc.transitmovementsrouter.models.CustomsOffice
import uk.gov.hmrc.transitmovementsrouter.models.MessageId
import uk.gov.hmrc.transitmovementsrouter.models.MessageType
import uk.gov.hmrc.transitmovementsrouter.models.MovementId

import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneOffset
import scala.concurrent.ExecutionContext.Implicits.global

class ServiceMonitoringConnectorSpec
    extends AnyFreeSpec
    with Matchers
    with WiremockSuite
    with TestActorSystem
    with HttpClientV2Support
    with ScalaFutures
    with ScalaCheckDrivenPropertyChecks
    with ModelGenerators {

  implicit val timeout: PatienceConfig = PatienceConfig(2.seconds, 2.seconds)
  implicit val hc: HeaderCarrier       = HeaderCarrier()

  private lazy val clock = Clock.fixed(LocalDateTime.of(2023, 6, 23, 15, 57, 58, 234000000).toInstant(ZoneOffset.UTC), ZoneOffset.UTC)

  val mockAppConfig: AppConfig = mock[AppConfig]
  when(mockAppConfig.serviceMonitoringUrl).thenReturn(s"http://localhost:$wiremockPort")
  when(mockAppConfig.serviceMonitoringIncomingUri).thenReturn("/incoming")
  when(mockAppConfig.serviceMonitoringOutgoingUri).thenReturn("/outgoing")

  "outgoing" - {
    "when successful" in forAll(arbitrary[MovementId], arbitrary[MessageId], arbitrary[MessageType], arbitrary[CustomsOffice]) {
      (movementId, messageId, messageType, customsOffice) =>
        server.resetAll()

        val expectedConversationId = ConversationId(movementId, messageId)

        server.stubFor(
          post("/outgoing")
            .withHeader("X-Message-Sender", equalTo(expectedConversationId.value.toString))
            .withHeader("Content-Type", equalTo("text/plain; charset=UTF-8"))
            .withRequestBody(
              equalToJson(
                s"""{
                  |    "id": "${expectedConversationId.value.toString}",
                  |    "timestamp": "2023-06-23T15:57:58.234",
                  |    "messageCode": "${messageType.code}",
                  |    "office": "${customsOffice.value.substring(0, 2)}"
                  |}
                  |""".stripMargin
              )
            )
            .willReturn(aResponse().withStatus(200))
        )
        val sut = new ServiceMonitoringConnectorImpl(mockAppConfig, httpClientV2, clock)
        whenReady(sut.outgoing(movementId, messageId, messageType, customsOffice)) {
          _ => succeed
        }

    }

    "when not successful" in forAll(arbitrary[MovementId], arbitrary[MessageId], arbitrary[MessageType], arbitrary[CustomsOffice]) {
      (movementId, messageId, messageType, customsOffice) =>
        server.resetAll()

        val expectedConversationId = ConversationId(movementId, messageId)

        server.stubFor(
          post("/outgoing")
            .withHeader("Content-Type", equalTo("text/plain; charset=UTF-8"))
            .withRequestBody(
              equalToJson(
                s"""{
                   |    "id": "${expectedConversationId.value.toString}",
                   |    "timestamp": "2023-06-23T15:57:58.234",
                   |    "messageCode": "${messageType.code}",
                   |    "office": "${customsOffice.value.substring(0, 2)}"
                   |}
                   |""".stripMargin
              )
            )
            .willReturn(aResponse().withStatus(500))
        )
        val sut = new ServiceMonitoringConnectorImpl(mockAppConfig, httpClientV2, clock)
        whenReady(
          sut
            .outgoing(movementId, messageId, messageType, customsOffice)
            .map(
              _ => fail("Expected a failure, got a success")
            )
            .recover {
              case UpstreamErrorResponse(_, 500, _, _) => (): Unit
            }
        ) {
          _ => succeed // will only execute if the upstream response was 500
        }

    }
  }

  "incoming" - {
    "when successful" in forAll(arbitrary[MovementId], arbitrary[MessageId], arbitrary[MessageType]) {
      (movementId, messageId, messageType) =>
        server.resetAll()

        val expectedConversationId = ConversationId(movementId, messageId)

        server.stubFor(
          post("/incoming")
            .withHeader("Content-Type", equalTo("text/plain; charset=UTF-8"))
            .withRequestBody(
              equalToJson(
                s"""{
                   |    "id": "${expectedConversationId.value.toString}",
                   |    "timestamp": "2023-06-23T15:57:58.234",
                   |    "messageCode": "${messageType.code}"
                   |}
                   |""".stripMargin
              )
            )
            .willReturn(aResponse().withStatus(200))
        )
        val sut = new ServiceMonitoringConnectorImpl(mockAppConfig, httpClientV2, clock)
        whenReady(sut.incoming(movementId, messageId, messageType)) {
          _ => succeed
        }

    }

    "when not successful" in forAll(arbitrary[MovementId], arbitrary[MessageId], arbitrary[MessageType]) {
      (movementId, messageId, messageType) =>
        server.resetAll()

        val expectedConversationId = ConversationId(movementId, messageId)

        server.stubFor(
          post("/incoming")
            .withHeader("Content-Type", equalTo("text/plain; charset=UTF-8"))
            .withRequestBody(
              equalToJson(
                s"""{
                   |    "id": "${expectedConversationId.value.toString}",
                   |    "timestamp": "2023-06-23T15:57:58.234",
                   |    "messageCode": "${messageType.code}"
                   |}
                   |""".stripMargin
              )
            )
            .willReturn(aResponse().withStatus(500))
        )
        val sut = new ServiceMonitoringConnectorImpl(mockAppConfig, httpClientV2, clock)
        whenReady(
          sut
            .incoming(movementId, messageId, messageType)
            .map(
              _ => fail("Expected a failure, got a success")
            )
            .recover {
              case UpstreamErrorResponse(_, 500, _, _) => (): Unit
            }
        ) {
          _ => succeed // will only execute if the upstream response was 500
        }

    }
  }

}
