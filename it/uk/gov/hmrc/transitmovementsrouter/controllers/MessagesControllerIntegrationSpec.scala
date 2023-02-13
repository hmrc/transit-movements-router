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

import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.matching.EqualToPattern
import com.github.tomakehurst.wiremock.matching.UrlPathPattern
import com.kenshoo.play.metrics.Metrics
import org.scalacheck.Gen
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import play.api.http.Status.CREATED
import play.api.http.Status.OK
import play.api.http.Status.UNAUTHORIZED
import play.api.inject.bind
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.inject.guice.GuiceableModule
import play.api.libs.json.Json
import play.api.test.FakeHeaders
import play.api.test.FakeRequest
import play.api.test.Helpers
import play.api.test.Helpers.defaultAwaitTimeout
import play.api.test.Helpers.running
import uk.gov.hmrc.transitmovementsrouter.it.base.TestMetrics
import uk.gov.hmrc.transitmovementsrouter.it.base.WiremockSuiteWithGuice
import uk.gov.hmrc.transitmovementsrouter.models.ConversationId
import uk.gov.hmrc.transitmovementsrouter.models.MessageId

import java.util.UUID

class MessagesControllerIntegrationSpec
    extends AnyFreeSpec
    with GuiceOneAppPerSuite
    with Matchers
    with WiremockSuiteWithGuice
    with ScalaCheckDrivenPropertyChecks {

  // We don't care about the content in this XML fragment, only the root tag and its child.
  val sampleXml: String =
    <TraderChannelResponse>
      <ncts:CC029C PhaseID="NCTS5.0" xmlns:ncts="http://ncts.dgtaxud.ec">
        <preparationDateAndTime>2022-05-25T09:37:04</preparationDateAndTime>
        <CustomsOfficeOfDeparture>
          <referenceNumber>GB1234567</referenceNumber>
        </CustomsOfficeOfDeparture>
      </ncts:CC029C>
    </TraderChannelResponse>.mkString

  override protected def appBuilder: GuiceApplicationBuilder =
    super.appBuilder.configure(
      "incomingRequestAuth.enabled"                                     -> true,
      "incomingRequestAuth.acceptedTokens.0"                            -> "ABC",
      "incomingRequestAuth.acceptedTokens.1"                            -> "123",
      "microservice.services.transit-movements.port"                    -> server.port().toString,
      "microservice.services.transit-movements-push-notifications.port" -> server.port().toString
    )

  override protected lazy val bindings: Seq[GuiceableModule] = Seq(
    bind[Metrics].to[TestMetrics]
  )

  "incoming" - {
    "when EIS makes a valid call with a valid body" - Seq("ABC", "123").foreach {
      authCode =>
        s"with auth code $authCode" in {
          // We do this instead of using the standard "app" because we otherwise get the error
          // "Trying to materialize stream after materializer has been shutdown".
          // We suspect it's due to nested tests.
          val newApp                  = appBuilder.build()
          val conversationId          = ConversationId(UUID.randomUUID())
          val (movementId, messageId) = conversationId.toMovementAndMessageId
          val outputMessageId         = Gen.stringOfN(16, Gen.hexChar.map(_.toLower)).map(MessageId).sample.get

          // We should hit the persistence layer on /transit-movements/traders/movements/${movementId.value}/messages?triggerId=messageId
          server.stubFor(
            post(new UrlPathPattern(new EqualToPattern(s"/transit-movements/traders/movements/${movementId.value}/messages"), false))
              .withQueryParam("triggerId", new EqualToPattern(messageId.value))
              .withHeader("x-message-type", new EqualToPattern("IE029"))
              .willReturn(
                aResponse().withStatus(OK).withBody(Json.stringify(Json.obj("messageId" -> outputMessageId.value)))
              )
          )

          server.stubFor(
            post(s"/transit-movements-push-notifications/traders/movements/${movementId.value}/messages/${outputMessageId.value}")
              .willReturn(
                aResponse().withStatus(OK)
              )
          )

          val eisRequest = FakeRequest(
            "POST",
            s"/transit-movements-router/movement/${conversationId.value.toString}/messages",
            FakeHeaders(
              Seq(
                "Authorization"    -> s"Bearer $authCode",
                "x-correlation-id" -> UUID.randomUUID().toString
              )
            ),
            Source.single(ByteString(sampleXml))
          )

          running(newApp) {
            val sut    = newApp.injector.instanceOf[MessagesController]
            val result = sut.incoming(conversationId)(eisRequest)

            Helpers.status(result) mustBe CREATED
          }
        }
    }

    "when EIS makes a call with an invalid authorization header with a valid body" in forAll(Gen.stringOfN(4, Gen.alphaNumChar)) {
      authCode =>
        val conversationId = ConversationId(UUID.randomUUID())

        val eisRequest = FakeRequest(
          "POST",
          s"/transit-movements-router/movement/${conversationId.value.toString}/messages",
          FakeHeaders(
            Seq(
              "Authorization"    -> s"Bearer $authCode",
              "x-correlation-id" -> UUID.randomUUID().toString
            )
          ),
          Source.single(ByteString(sampleXml))
        )

        running(app) {
          val sut    = app.injector.instanceOf[MessagesController]
          val result = sut.incoming(conversationId)(eisRequest)

          Helpers.status(result) mustBe UNAUTHORIZED
        }
    }
  }

}
