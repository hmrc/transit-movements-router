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
import com.github.tomakehurst.wiremock.matching.AnythingPattern
import com.github.tomakehurst.wiremock.matching.EqualToPattern
import com.github.tomakehurst.wiremock.matching.StringValuePattern
import com.github.tomakehurst.wiremock.matching.UrlPathPattern
import com.kenshoo.play.metrics.Metrics
import org.scalacheck.Gen
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import play.api.http.Status._
import play.api.http.HeaderNames
import play.api.http.MimeTypes
import play.api.inject.bind
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.inject.guice.GuiceableModule
import play.api.libs.json.Json
import play.api.test.Helpers.defaultAwaitTimeout
import play.api.test.Helpers.running
import play.api.test.FakeHeaders
import play.api.test.FakeRequest
import play.api.test.Helpers
import uk.gov.hmrc.transitmovementsrouter.it.base.RegexPatterns
import uk.gov.hmrc.transitmovementsrouter.it.base.TestMetrics
import uk.gov.hmrc.transitmovementsrouter.it.base.WiremockSuiteWithGuice
import uk.gov.hmrc.transitmovementsrouter.models.ConversationId
import uk.gov.hmrc.transitmovementsrouter.models.EoriNumber
import uk.gov.hmrc.transitmovementsrouter.models.MessageId
import uk.gov.hmrc.transitmovementsrouter.models.MovementType

import java.time.format.DateTimeFormatter
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID

class MessagesControllerIntegrationSpec
    extends AnyFreeSpec
    with GuiceOneAppPerSuite
    with Matchers
    with WiremockSuiteWithGuice
    with ScalaCheckDrivenPropertyChecks {

  // We don't care about the content in this XML fragment, only the root tag and its child.
  val sampleIncomingXml: String =
    <n1:TraderChannelResponse xmlns:txd="http://ncts.dgtaxud.ec"
                              xmlns:n1="http://www.hmrc.gov.uk/eis/ncts5/v1"
                              xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                              xsi:schemaLocation="http://www.hmrc.gov.uk/eis/ncts5/v1 EIS_WrapperV10_TraderChannelSubmission-51.8.xsd">
      <txd:CC029C PhaseID="NCTS5.0">
        <preparationDateAndTime>2022-05-25T09:37:04</preparationDateAndTime>
        <CustomsOfficeOfDeparture>
          <referenceNumber>GB1234567</referenceNumber>
        </CustomsOfficeOfDeparture>
      </txd:CC029C>
    </n1:TraderChannelResponse>.mkString

  // We don't care about the content in this XML fragment, only the root tag and its child.
  val sampleOutgoingXml: String =
    <ncts:CC015C PhaseID="NCTS5.0" xmlns:ncts="http://ncts.dgtaxud.ec">
      <preparationDateAndTime>2022-05-25T09:37:04</preparationDateAndTime>
      <CustomsOfficeOfDeparture>
        <referenceNumber>GB1234567</referenceNumber>
      </CustomsOfficeOfDeparture>
    </ncts:CC015C>.mkString

  val sampleOutgoingXIXml: String =
    <ncts:CC015C PhaseID="NCTS5.0" xmlns:ncts="http://ncts.dgtaxud.ec">
      <preparationDateAndTime>2022-05-25T09:37:04</preparationDateAndTime>
      <CustomsOfficeOfDeparture>
        <referenceNumber>XI1234567</referenceNumber>
      </CustomsOfficeOfDeparture>
    </ncts:CC015C>.mkString

  val brokenXml: String =
    """<nope>
      <ncts:CC029C xmlns:ncts="http://ncts.dgtaxud.ec" PhaseID="NCTS5.0">
        <preparationDateAndTime>2022-05-25T09:37:04</preparationDateAndTime>
        <CustomsOfficeOfDeparture>
          <referenceNumber>GB1234567</referenceNumber>
        </CustomsOfficeOfDeparture>
      </ncts:CC029C>
    </nope>"""

  override protected def appBuilder: GuiceApplicationBuilder =
    super.appBuilder.configure(
      "incomingRequestAuth.enabled"                                     -> true,
      "incomingRequestAuth.acceptedTokens.0"                            -> "ABC",
      "incomingRequestAuth.acceptedTokens.1"                            -> "123",
      "microservice.services.transit-movements.port"                    -> server.port().toString,
      "microservice.services.transit-movements-push-notifications.port" -> server.port().toString,
      "microservice.services.eis.gb.uri"                                -> "/gb",
      "microservice.services.eis.xi.uri"                                -> "/xi",
      "microservice.services.eis.gb.retry.max-retries"                  -> 0
    )

  lazy val anything: StringValuePattern = new AnythingPattern()

  override protected lazy val bindings: Seq[GuiceableModule] = Seq(
    bind[Metrics].to[TestMetrics]
  )

  "outgoing" - {
    "with a valid body routing to GB, return 202" in {
      // We do this instead of using the standard "app" because we otherwise get the error
      // "Trying to materialize stream after materializer has been shutdown".
      // We suspect it's due to nested tests.
      val newApp                  = appBuilder.build()
      val conversationId          = ConversationId(UUID.randomUUID())
      val (movementId, messageId) = conversationId.toMovementAndMessageId

      server.stubFor(
        post(
          urlEqualTo("/gb")
        )
          .withHeader(HeaderNames.AUTHORIZATION, equalTo(s"Bearer bearertoken"))
          .withHeader("Date", anything)
          .withHeader("X-Correlation-Id", matching(RegexPatterns.UUID))
          .withHeader("X-Conversation-Id", equalTo(conversationId.value.toString))
          .withHeader("CustomProcessHost", equalTo("Digital"))
          .withHeader(HeaderNames.ACCEPT, equalTo("application/xml"))
          .withHeader(HeaderNames.CONTENT_TYPE, equalTo("application/xml"))
          .willReturn(aResponse().withStatus(OK))
      )

      val apiRequest = FakeRequest(
        "POST",
        s"/traders/GB0123456789/movements/departures/${movementId.value}/messages/${messageId.value}",
        FakeHeaders(
          Seq(
            "x-message-type"         -> "IE015",
            "x-request-id"           -> UUID.randomUUID().toString,
            HeaderNames.CONTENT_TYPE -> MimeTypes.XML
          )
        ),
        Source.single(ByteString(sampleOutgoingXml))
      )

      running(newApp) {
        val sut    = newApp.injector.instanceOf[MessagesController]
        val result = sut.outgoing(EoriNumber("GB0123456789"), MovementType("departures"), movementId, messageId)(apiRequest)

        Helpers.status(result) mustBe ACCEPTED
      }
    }

    "with a valid body routing to XI, return 202" in {
      // We do this instead of using the standard "app" because we otherwise get the error
      // "Trying to materialize stream after materializer has been shutdown".
      // We suspect it's due to nested tests.
      val newApp                  = appBuilder.build()
      val conversationId          = ConversationId(UUID.randomUUID())
      val (movementId, messageId) = conversationId.toMovementAndMessageId

      val time      = OffsetDateTime.of(2023, 2, 14, 15, 55, 28, 0, ZoneOffset.UTC)
      val formatted = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss z", Locale.ENGLISH).withZone(ZoneOffset.UTC).format(time)

      server.stubFor(
        post(
          urlEqualTo("/xi")
        )
          .withHeader(HeaderNames.AUTHORIZATION, equalTo(s"Bearer bearertoken"))
          .withHeader("Date", anything)
          .withHeader("X-Correlation-Id", matching(RegexPatterns.UUID))
          .withHeader("X-Conversation-Id", equalTo(conversationId.value.toString))
          .withHeader("CustomProcessHost", equalTo("Digital"))
          .withHeader(HeaderNames.ACCEPT, equalTo("application/xml"))
          .withHeader(HeaderNames.CONTENT_TYPE, equalTo("application/xml"))
          .willReturn(aResponse().withStatus(OK))
      )

      val apiRequest = FakeRequest(
        "POST",
        s"/traders/GB0123456789/movements/departures/${movementId.value}/messages/${messageId.value}",
        FakeHeaders(
          Seq(
            "Date"                   -> formatted,
            "x-message-type"         -> "IE015",
            "x-request-id"           -> UUID.randomUUID().toString,
            HeaderNames.CONTENT_TYPE -> MimeTypes.XML
          )
        ),
        Source.single(ByteString(sampleOutgoingXIXml))
      )

      running(newApp) {
        val sut    = newApp.injector.instanceOf[MessagesController]
        val result = sut.outgoing(EoriNumber("GB0123456789"), MovementType("departures"), movementId, messageId)(apiRequest)

        Helpers.status(result) mustBe ACCEPTED
      }
    }

    "with an incorrectly set bearer token routing to GB, return 500" in {
      // We do this instead of using the standard "app" because we otherwise get the error
      // "Trying to materialize stream after materializer has been shutdown".
      // We suspect it's due to nested tests.
      val newApp                  = appBuilder.build()
      val conversationId          = ConversationId(UUID.randomUUID())
      val (movementId, messageId) = conversationId.toMovementAndMessageId

      server.stubFor(
        post(
          urlEqualTo("/gb")
        )
          .withHeader(HeaderNames.AUTHORIZATION, equalTo(s"Bearer bearertoken"))
          .withHeader("Date", anything)
          .withHeader("X-Correlation-Id", matching(RegexPatterns.UUID))
          .withHeader("X-Conversation-Id", equalTo(conversationId.value.toString))
          .withHeader("CustomProcessHost", equalTo("Digital"))
          .withHeader(HeaderNames.ACCEPT, equalTo("application/xml"))
          .withHeader(HeaderNames.CONTENT_TYPE, equalTo("application/xml"))
          .willReturn(aResponse().withStatus(FORBIDDEN))
      )

      val apiRequest = FakeRequest(
        "POST",
        s"/traders/GB0123456789/movements/departures/${movementId.value}/messages/${messageId.value}",
        FakeHeaders(
          Seq(
            "x-message-type"         -> "IE015",
            "x-request-id"           -> UUID.randomUUID().toString,
            HeaderNames.CONTENT_TYPE -> MimeTypes.XML
          )
        ),
        Source.single(ByteString(sampleOutgoingXml))
      )

      running(newApp) {
        val sut    = newApp.injector.instanceOf[MessagesController]
        val result = sut.outgoing(EoriNumber("GB0123456789"), MovementType("departures"), movementId, messageId)(apiRequest)

        Helpers.status(result) mustBe INTERNAL_SERVER_ERROR
      }
    }

    "with XML containing the incorrect message type (no reference number in the expected place), return 400" in {
      // We do this instead of using the standard "app" because we otherwise get the error
      // "Trying to materialize stream after materializer has been shutdown".
      // We suspect it's due to nested tests.
      val newApp                  = appBuilder.build()
      val conversationId          = ConversationId(UUID.randomUUID())
      val (movementId, messageId) = conversationId.toMovementAndMessageId

      val time      = OffsetDateTime.of(2023, 2, 14, 15, 55, 28, 0, ZoneOffset.UTC)
      val formatted = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss z", Locale.ENGLISH).withZone(ZoneOffset.UTC).format(time)

      val apiRequest = FakeRequest(
        "POST",
        s"/traders/GB0123456789/movements/departures/${movementId.value}/messages/${messageId.value}",
        FakeHeaders(
          Seq(
            "Date"                   -> formatted,
            "x-message-type"         -> "IE015",
            "x-request-id"           -> UUID.randomUUID().toString,
            HeaderNames.CONTENT_TYPE -> MimeTypes.XML
          )
        ),
        Source.single(ByteString(sampleIncomingXml)) // note this is the IE029, not the IE015
      )

      running(newApp) {
        val sut    = newApp.injector.instanceOf[MessagesController]
        val result = sut.outgoing(EoriNumber("GB0123456789"), MovementType("departures"), movementId, messageId)(apiRequest)

        Helpers.status(result) mustBe BAD_REQUEST
      }
    }

    "with broken XML, return 400" in {
      // We do this instead of using the standard "app" because we otherwise get the error
      // "Trying to materialize stream after materializer has been shutdown".
      // We suspect it's due to nested tests.
      val newApp                  = appBuilder.build()
      val conversationId          = ConversationId(UUID.randomUUID())
      val (movementId, messageId) = conversationId.toMovementAndMessageId

      val time      = OffsetDateTime.of(2023, 2, 14, 15, 55, 28, 0, ZoneOffset.UTC)
      val formatted = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss z", Locale.ENGLISH).withZone(ZoneOffset.UTC).format(time)

      val apiRequest = FakeRequest(
        "POST",
        s"/traders/GB0123456789/movements/departures/${movementId.value}/messages/${messageId.value}",
        FakeHeaders(
          Seq(
            "Date"                   -> formatted,
            "x-message-type"         -> "IE015",
            "x-request-id"           -> UUID.randomUUID().toString,
            HeaderNames.CONTENT_TYPE -> MimeTypes.XML
          )
        ),
        Source.single(ByteString(brokenXml))
      )

      running(newApp) {
        val sut    = newApp.injector.instanceOf[MessagesController]
        val result = sut.outgoing(EoriNumber("GB0123456789"), MovementType("departures"), movementId, messageId)(apiRequest)

        Helpers.status(result) mustBe BAD_REQUEST
      }
    }
  }

  "incoming" - {
    "when EIS makes a valid call with a valid body, return 201" - Seq("ABC", "123").foreach {
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
            Source.single(ByteString(sampleIncomingXml))
          )

          running(newApp) {
            val sut    = newApp.injector.instanceOf[MessagesController]
            val result = sut.incoming(conversationId)(eisRequest)

            Helpers.status(result) mustBe CREATED
          }
        }
    }

    "when EIS makes a call with an invalid authorization header with an invalid body, return 400" in {
      val newApp         = appBuilder.build()
      val conversationId = ConversationId(UUID.randomUUID())

      val eisRequest = FakeRequest(
        "POST",
        s"/transit-movements-router/movement/${conversationId.value.toString}/messages",
        FakeHeaders(
          Seq(
            "Authorization"    -> s"Bearer ABC",
            "x-correlation-id" -> UUID.randomUUID().toString
          )
        ),
        Source.single(ByteString(brokenXml))
      )

      running(newApp) {
        val sut    = newApp.injector.instanceOf[MessagesController]
        val result = sut.incoming(conversationId)(eisRequest)

        Helpers.status(result) mustBe BAD_REQUEST
      }
    }

    "when EIS makes a call with an invalid authorization header with a valid body, return 401" in forAll(Gen.stringOfN(4, Gen.alphaNumChar)) {
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
          Source.single(ByteString(sampleIncomingXml))
        )

        running(app) {
          val sut    = app.injector.instanceOf[MessagesController]
          val result = sut.incoming(conversationId)(eisRequest)

          Helpers.status(result) mustBe UNAUTHORIZED
        }
    }
  }

}
