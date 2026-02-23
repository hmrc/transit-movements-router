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
import com.github.tomakehurst.wiremock.stubbing.Scenario
import org.apache.pekko.stream.scaladsl.Source
import org.mockito.ArgumentMatchers.any
import org.apache.pekko.util.ByteString
import org.mockito.Mockito.when
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Gen
import org.scalatest.concurrent.IntegrationPatience
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.must.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.time.SpanSugar.convertIntToGrainOfTime
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import play.api.http.HeaderNames
import play.api.http.MimeTypes
import play.api.test.Helpers._
import retry.RetryPolicies
import retry.RetryPolicy
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.HttpResponse
import uk.gov.hmrc.http.RequestId
import uk.gov.hmrc.http.UpstreamErrorResponse
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.client.RequestBuilder
import uk.gov.hmrc.http.test.HttpClientV2Support
import uk.gov.hmrc.transitmovementsrouter.config.CircuitBreakerConfig
import uk.gov.hmrc.transitmovementsrouter.config.EISInstanceConfig
import uk.gov.hmrc.transitmovementsrouter.config.Headers
import uk.gov.hmrc.transitmovementsrouter.config.RetryConfig
import uk.gov.hmrc.transitmovementsrouter.it.base.RegexPatterns
import uk.gov.hmrc.transitmovementsrouter.it.base.TestActorSystem
import uk.gov.hmrc.transitmovementsrouter.it.base.WiremockSuite
import uk.gov.hmrc.transitmovementsrouter.it.generators.ModelGenerators
import uk.gov.hmrc.transitmovementsrouter.models.ConversationId
import uk.gov.hmrc.transitmovementsrouter.models.LocalReferenceNumber
import uk.gov.hmrc.transitmovementsrouter.models.MessageId
import uk.gov.hmrc.transitmovementsrouter.models.MovementId
import uk.gov.hmrc.transitmovementsrouter.models.errors.RoutingError
import uk.gov.hmrc.transitmovementsrouter.models.errors.RoutingError.Upstream

import java.net.URL
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import cats.implicits.catsStdInstancesForFuture

class EISConnectorSpec
    extends AnyWordSpec
    with HttpClientV2Support
    with Matchers
    with WiremockSuite
    with ScalaFutures
    with MockitoSugar
    with IntegrationPatience
    with ScalaCheckPropertyChecks
    with TableDrivenPropertyChecks
    with TestActorSystem
    with ModelGenerators {

  private object NoRetries extends Retries {

    override def createRetryPolicy(config: RetryConfig)(implicit
      ec: ExecutionContext
    ): RetryPolicy[Future] =
      RetryPolicies.alwaysGiveUp[Future]
  }

  private object OneRetry extends Retries {

    override def createRetryPolicy(config: RetryConfig)(implicit
      ec: ExecutionContext
    ): RetryPolicy[Future] =
      RetryPolicies.limitRetries[Future](1)
  }

  val uriStub = "/transit-movements-eis-stub/movements/messages"

  val connectorConfig: EISInstanceConfig = EISInstanceConfig(
    "http",
    "localhost",
    wiremockPort,
    uriStub,
    Headers("bearertokenhereGB", "mdtp", "application/xml"),
    CircuitBreakerConfig(
      3,
      5.seconds,
      5.seconds,
      10.seconds,
      1,
      0
    ),
    RetryConfig(
      1,
      1.second,
      5.seconds
    ),
    forwardClientId = false
  )

  private val clock = Clock.fixed(OffsetDateTime.of(2023, 4, 13, 10, 34, 41, 500, ZoneOffset.UTC).toInstant, ZoneOffset.UTC)

  // We construct the connector each time to avoid issues with the circuit breaker
  def noRetriesConnector = new EISConnectorImpl("NoRetry", connectorConfig, httpClientV2, NoRetries, clock, true)
  def oneRetryConnector  = new EISConnectorImpl("OneRetry", connectorConfig, httpClientV2, OneRetry, clock, true)

  def forwardClientIdConnector =
    new EISConnectorImpl("ForwardClient", connectorConfig.copy(forwardClientId = true), httpClientV2, NoRetries, clock, true)

  lazy val connectorGen: Gen[() => EISConnector] = Gen.oneOf(() => noRetriesConnector, () => oneRetryConnector)

  def source: Source[ByteString, ?] = Source.single(ByteString.fromString("<test></test>"))

  "post" should {

    "add X-Correlation-Id and X-Conversation-Id and Date headers to messages for GB" in forAll(
      connectorGen,
      arbitrary[MovementId],
      arbitrary[MessageId]
    ) { (connector, movementId, messageId) =>
      server.resetAll() // Need to reset due to the forAll - it's technically the same test

      // Important note: while this test considers successes, as this connector has a retry function,
      // we have to ensure that any success result is not retried. To do this, we make the stub return
      // a 202 status the first time it is called, then we transition it into a state where it'll return
      // an error. As the retry algorithm should not attempt a retry on a 202, the stub should only be
      // called once - so a 500 should never be returned.
      //
      // If a 500 error is returned, this most likely means a retry happened, the first place to look
      // should be the code the determines if a result is successful.

      val expectedConversationId = ConversationId(movementId, messageId)
      val requestId              = UUID.randomUUID().toString

      def stub(currentState: String, targetState: String, codeToReturn: Int) =
        server.stubFor(
          post(
            urlEqualTo(uriStub)
          )
            .inScenario("Standard Call")
            .whenScenarioStateIs(currentState)
            .withHeader(HeaderNames.AUTHORIZATION, equalTo("Bearer bearertokenhereGB"))
            .withHeader("Date", equalTo("Thu, 13 Apr 2023 10:34:41 UTC"))
            .withHeader("X-Request-Id", equalTo(requestId))
            .withHeader("X-Correlation-Id", matching(RegexPatterns.UUID))
            .withHeader("X-Conversation-Id", equalTo(expectedConversationId.value.toString))
            .withHeader(HeaderNames.ACCEPT, equalTo("application/xml"))
            .withHeader(HeaderNames.CONTENT_TYPE, equalTo("application/xml"))
            .withHeader(HeaderNames.X_FORWARDED_HOST, equalTo("mdtp"))
            .willReturn(aResponse().withStatus(codeToReturn))
            .willSetStateTo(targetState)
        )

      val secondState = "should now fail"

      stub(Scenario.STARTED, secondState, OK)
      stub(secondState, secondState, INTERNAL_SERVER_ERROR)

      // allows us to ensure the logs are correctly grabbing the request ID and message ID, by eye for the moment
      val hc = HeaderCarrier(
        requestId = Some(RequestId(requestId)),
        extraHeaders = Seq("X-Message-Type" -> "IE015")
      )

      whenReady(connector().post(movementId, messageId, source, hc)) {
        _.isRight mustBe true
      }
    }

    "add X-Correlation-Id and X-Conversation-Id headers, but retain the Date header, to messages for GB" in forAll(
      connectorGen,
      arbitrary[MovementId],
      arbitrary[MessageId]
    ) { (connector, movementId, messageId) =>
      server.resetAll() // Need to reset due to the forAll - it's technically the same test

      // Important note: while this test considers successes, as this connector has a retry function,
      // we have to ensure that any success result is not retried. To do this, we make the stub return
      // a 202 status the first time it is called, then we transition it into a state where it'll return
      // an error. As the retry algorithm should not attempt a retry on a 202, the stub should only be
      // called once - so a 500 should never be returned.
      //
      // If a 500 error is returned, this most likely means a retry happened, the first place to look
      // should be the code the determines if a result is successful.

      val expectedConversationId = ConversationId(movementId, messageId)

      def stub(currentState: String, targetState: String, codeToReturn: Int) =
        server.stubFor(
          post(
            urlEqualTo(uriStub)
          )
            .inScenario("Standard Call")
            .whenScenarioStateIs(currentState)
            .withHeader("Date", equalTo("Thu, 13 Apr 2023 10:34:41 UTC"))
            .withHeader("X-Correlation-Id", matching(RegexPatterns.UUID))
            .withHeader("X-Conversation-Id", equalTo(expectedConversationId.value.toString))
            .withHeader(HeaderNames.ACCEPT, equalTo("application/xml"))
            .withHeader(HeaderNames.CONTENT_TYPE, equalTo("application/xml"))
            .withHeader(HeaderNames.X_FORWARDED_HOST, equalTo("mdtp"))
            .willReturn(aResponse().withStatus(codeToReturn))
            .willSetStateTo(targetState)
        )

      val secondState = "should now fail"

      stub(Scenario.STARTED, secondState, OK)
      stub(secondState, secondState, INTERNAL_SERVER_ERROR)

      val hc = HeaderCarrier()

      whenReady(connector().post(movementId, messageId, source, hc)) {
        _.isRight mustBe true
      }
    }

    "return a unit when post is successful" in forAll(connectorGen, arbitrary[MovementId], arbitrary[MessageId]) { (connector, movementId, messageId) =>
      server.resetAll() // Need to reset due to the forAll - it's technically the same test

      val expectedConversationId = ConversationId(movementId, messageId)

      def stub(currentState: String, targetState: String, codeToReturn: Int) =
        server.stubFor(
          post(
            urlEqualTo(uriStub)
          ).withHeader("Authorization", equalTo("Bearer bearertokenhereGB"))
            .withHeader("Date", equalTo("Thu, 13 Apr 2023 10:34:41 UTC"))
            .withHeader(HeaderNames.ACCEPT, equalTo("application/xml"))
            .withHeader(HeaderNames.CONTENT_TYPE, equalTo("application/xml"))
            .withHeader(HeaderNames.X_FORWARDED_HOST, equalTo("mdtp"))
            .withHeader("X-Correlation-Id", matching(RegexPatterns.UUID))
            .withHeader("X-Conversation-Id", equalTo(expectedConversationId.value.toString))
            .inScenario("Standard Call")
            .whenScenarioStateIs(currentState)
            .willReturn(aResponse().withStatus(codeToReturn))
            .willSetStateTo(targetState)
        )

      val secondState = "should now fail"

      stub(Scenario.STARTED, secondState, OK)
      stub(secondState, secondState, INTERNAL_SERVER_ERROR)

      val hc = HeaderCarrier()

      whenReady(connector().post(movementId, messageId, source, hc)) {
        _.isRight mustBe true
      }
    }

    "return a unit when post is successful and the client id is passed in the request" in forAll(arbitrary[MovementId], arbitrary[MessageId]) {
      (movementId, messageId) =>
        server.resetAll() // Need to reset due to the forAll - it's technically the same test

        val expectedConversationId = ConversationId(movementId, messageId)

        def stub(currentState: String, codeToReturn: Int) =
          server.stubFor(
            post(
              urlEqualTo(uriStub)
            ).withHeader("Authorization", equalTo("Bearer bearertokenhereGB"))
              .withHeader("Date", equalTo("Thu, 13 Apr 2023 10:34:41 UTC"))
              .withHeader(HeaderNames.ACCEPT, equalTo("application/xml"))
              .withHeader(HeaderNames.CONTENT_TYPE, equalTo("application/xml"))
              .withHeader(HeaderNames.X_FORWARDED_HOST, equalTo("mdtp"))
              .withHeader("X-Correlation-Id", matching(RegexPatterns.UUID))
              .withHeader("X-Conversation-Id", equalTo(expectedConversationId.value.toString))
              .withHeader("X-Client-Id", equalTo("CLIENT_ABC"))
              .inScenario("Standard Call")
              .whenScenarioStateIs(currentState)
              .willReturn(aResponse().withStatus(codeToReturn))
          )

        stub(Scenario.STARTED, OK)

        val hc = HeaderCarrier(
          otherHeaders = Seq("X-Client-Id" -> "CLIENT_ABC")
        )

        whenReady(forwardClientIdConnector.post(movementId, messageId, source, hc)) {
          _.isRight mustBe true
        }
    }

    "return unit when post is successful on retry if there is an initial failure" in forAll(arbitrary[MovementId], arbitrary[MessageId]) {
      (movementId, messageId) =>
        val expectedConversationId = ConversationId(movementId, messageId)

        def stub(currentState: String, targetState: String, codeToReturn: Int) =
          server.stubFor(
            post(
              urlEqualTo(uriStub)
            )
              .inScenario("Flaky Call")
              .whenScenarioStateIs(currentState)
              .willSetStateTo(targetState)
              .withHeader("Date", equalTo("Thu, 13 Apr 2023 10:34:41 UTC"))
              .withHeader("Authorization", equalTo("Bearer bearertokenhereGB"))
              .withHeader(HeaderNames.ACCEPT, equalTo("application/xml"))
              .withHeader(HeaderNames.CONTENT_TYPE, equalTo("application/xml"))
              .withHeader(HeaderNames.X_FORWARDED_HOST, equalTo("mdtp"))
              .withHeader("X-Correlation-Id", matching(RegexPatterns.UUID))
              .withHeader("X-Conversation-Id", equalTo(expectedConversationId.value.toString))
              .willReturn(aResponse().withStatus(codeToReturn))
          )

        val secondState = "should now succeed"

        stub(Scenario.STARTED, secondState, INTERNAL_SERVER_ERROR)
        stub(secondState, secondState, OK)

        val hc = HeaderCarrier()

        whenReady(oneRetryConnector.post(movementId, messageId, source, hc)) {
          _.isRight mustBe true
        }
    }

    val errorCodes = Gen.oneOf(
      Seq(
        BAD_REQUEST,
        FORBIDDEN,
        INTERNAL_SERVER_ERROR,
        BAD_GATEWAY,
        GATEWAY_TIMEOUT
      )
    )

    "pass through error status codes" in forAll(errorCodes, connectorGen, arbitrary[MovementId], arbitrary[MessageId]) {
      (statusCode, connector, movementId, messageId) =>
        server.resetAll() // Need to reset due to the forAll - it's technically the same test

        val expectedConversationId = ConversationId(movementId, messageId)

        server.stubFor(
          post(
            urlEqualTo(uriStub)
          ).withHeader("Authorization", equalTo("Bearer bearertokenhereGB"))
            .withHeader(HeaderNames.ACCEPT, equalTo("application/xml"))
            .withHeader("Date", equalTo("Thu, 13 Apr 2023 10:34:41 UTC"))
            .withHeader(HeaderNames.CONTENT_TYPE, equalTo("application/xml"))
            .withHeader(HeaderNames.X_FORWARDED_HOST, equalTo("mdtp"))
            .withHeader("X-Correlation-Id", matching(RegexPatterns.UUID))
            .withHeader("X-Conversation-Id", equalTo(expectedConversationId.value.toString))
            .willReturn(aResponse().withStatus(statusCode))
        )

        val hc = HeaderCarrier()

        whenReady(connector().post(movementId, messageId, source, hc)) {
          case Left(x) if x.isInstanceOf[Upstream] =>
            x.asInstanceOf[RoutingError.Upstream].upstreamErrorResponse.statusCode mustBe statusCode
          case x =>
            fail("Left was not a RoutingError.Upstream: " + x)
        }
    }
  }

  "handle exceptions by returning an HttpResponse with status code 500" in forAll(arbitrary[MovementId], arbitrary[MessageId]) { (movementId, messageId) =>
    val httpClientV2 = mock[HttpClientV2]

    val hc        = HeaderCarrier()
    val connector = new EISConnectorImpl("Failure", connectorConfig, httpClientV2, NoRetries, clock, true)

    val mockRequestBuilder = mock[RequestBuilder]

    when(mockRequestBuilder.setHeader(any)).thenReturn(mockRequestBuilder)
    when(mockRequestBuilder.execute[HttpResponse](using any(), any())).thenReturn(Future.failed(new RuntimeException))
    when(mockRequestBuilder.withBody(any())(using any(), any(), any())).thenReturn(mockRequestBuilder)
    when(httpClientV2.post(any[URL])(any[HeaderCarrier])).thenReturn(mockRequestBuilder)

    whenReady(connector.post(movementId, messageId, source, hc)) {
      case Left(x) if x.isInstanceOf[RoutingError.Unexpected] => x.asInstanceOf[RoutingError.Unexpected].cause.get mustBe a[RuntimeException]
      case _                                                  => fail("Left was not a RoutingError.Unexpected")
    }
  }

  "return an Upstream an HttpResponse with status code 403 when the body is not the LRN duplication error, and should only run once" in forAll(
    arbitrary[MovementId],
    arbitrary[MessageId]
  ) { (movementId, messageId) =>
    val errorXml =
      """<not>a</not>
          |""".stripMargin

    val hc = HeaderCarrier()
    server.resetAll()
    val connector = new EISConnectorImpl("Failure", connectorConfig, httpClientV2, OneRetry, clock, true)

    val expectedConversationId = ConversationId(movementId, messageId)

    server.stubFor(
      post(
        urlEqualTo(uriStub)
      ).withHeader("Authorization", equalTo("Bearer bearertokenhereGB"))
        .withHeader(HeaderNames.ACCEPT, equalTo("application/xml"))
        .withHeader("Date", equalTo("Thu, 13 Apr 2023 10:34:41 UTC"))
        .withHeader(HeaderNames.CONTENT_TYPE, equalTo("application/xml"))
        .withHeader(HeaderNames.X_FORWARDED_HOST, equalTo("mdtp"))
        .withHeader("X-Correlation-Id", matching(RegexPatterns.UUID))
        .withHeader("X-Conversation-Id", equalTo(expectedConversationId.value.toString))
        .willReturn(
          aResponse()
            .withStatus(FORBIDDEN)
            .withHeader(HeaderNames.CONTENT_TYPE, MimeTypes.XML)
            .withBody(errorXml)
        )
    )

    whenReady(connector.post(movementId, messageId, source, hc)) {
      case Left(Upstream(UpstreamErrorResponse(_, 403, _, _))) => succeed
      case x                                                   => fail(s"Left was not a RoutingError.Upstream, instead got $x")
    }

    server.verify(
      1,
      postRequestedFor(
        urlEqualTo(uriStub)
      )
    )
  }

  "post should called only once and no retry for Duplicate LRN error" in forAll(arbitrary[MovementId], arbitrary[MessageId]) { (movementId, messageId) =>
    val errorXml =
      """<?xml version="1.0" encoding="UTF-8"?>
          |<lrnDuplicationErrorDetail xmlns:eis="http://www.hmrc.gsi.gov.uk/eis">
          |    <timestamp>Fri, 03 Jul 2020 11:20:25 UTC</timestamp>
          |    <correlationId>00000000-0000-0000-0000-000000000000</correlationId>
          |    <errorCode>400</errorCode>
          |    <errorMessage>The supplied LRN: SIMPL-RQ-2023052301 has already been used by submitter: swp.transit.agent</errorMessage>
          |    <source>ERMIS</source>
          |    <sourceFaultDetail>
          |        <submitter>swp.transit.agent</submitter>
          |        <lrn>LRN1234</lrn>
          |    </sourceFaultDetail>
          |</lrnDuplicationErrorDetail>
          |""".stripMargin

    val hc = HeaderCarrier()
    server.resetAll()
    val connector = new EISConnectorImpl("Failure", connectorConfig, httpClientV2, OneRetry, clock, true)

    val expectedConversationId = ConversationId(movementId, messageId)

    server.stubFor(
      post(
        urlEqualTo(uriStub)
      ).withHeader("Authorization", equalTo("Bearer bearertokenhereGB"))
        .withHeader(HeaderNames.ACCEPT, equalTo("application/xml"))
        .withHeader("Date", equalTo("Thu, 13 Apr 2023 10:34:41 UTC"))
        .withHeader(HeaderNames.CONTENT_TYPE, equalTo("application/xml"))
        .withHeader(HeaderNames.X_FORWARDED_HOST, equalTo("mdtp"))
        .withHeader("X-Correlation-Id", matching(RegexPatterns.UUID))
        .withHeader("X-Conversation-Id", equalTo(expectedConversationId.value.toString))
        .willReturn(
          aResponse()
            .withStatus(FORBIDDEN)
            .withHeader(HeaderNames.CONTENT_TYPE, MimeTypes.XML)
            .withBody(errorXml)
        )
    )

    whenReady(connector.post(movementId, messageId, source, hc)) {
      case Left(RoutingError.DuplicateLRNError(_, _, LocalReferenceNumber("LRN1234"))) => succeed
      case x                                                                           => fail(s"Left was not a RoutingError.DuplicateLRNError: instead got $x")
    }

    server.verify(
      1,
      postRequestedFor(
        urlEqualTo(uriStub)
      )
    )
  }
}
