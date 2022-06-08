/*
 * Copyright 2022 HM Revenue & Customs
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
import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.stubbing.Scenario
import org.mockito.ArgumentMatchers
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.when
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
import play.api.libs.ws.WSClient
import play.api.libs.ws.WSRequest
import play.api.libs.ws.ahc.AhcWSClient
import play.api.test.Helpers._
import retry.RetryPolicies
import retry.RetryPolicy
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.transitmovementsrouter.base.TestActorSystem
import uk.gov.hmrc.transitmovementsrouter.base.TestHelpers
import uk.gov.hmrc.transitmovementsrouter.config.CircuitBreakerConfig
import uk.gov.hmrc.transitmovementsrouter.config.EISInstanceConfig
import uk.gov.hmrc.transitmovementsrouter.config.Headers
import uk.gov.hmrc.transitmovementsrouter.config.RetryConfig
import uk.gov.hmrc.transitmovementsrouter.models.MessageSender
import uk.gov.hmrc.transitmovementsrouter.service.error.RoutingError

import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class MessageConnectorSpec
    extends AnyWordSpec
    with Matchers
    with WiremockSuite
    with ScalaFutures
    with MockitoSugar
    with IntegrationPatience
    with ScalaCheckPropertyChecks
    with TableDrivenPropertyChecks
    with TestActorSystem {

  private object NoRetries extends Retries {

    override def createRetryPolicy(config: RetryConfig)(implicit
      ec: ExecutionContext
    ): RetryPolicy[Future] =
      RetryPolicies.alwaysGiveUp[Future](cats.implicits.catsStdInstancesForFuture(ec))
  }

  private object OneRetry extends Retries {

    override def createRetryPolicy(config: RetryConfig)(implicit
      ec: ExecutionContext
    ): RetryPolicy[Future] =
      RetryPolicies.limitRetries[Future](1)(cats.implicits.catsStdInstancesForFuture(ec))
  }

  val uriStub = "/transit-movements-eis-stub/movements/messages"

  val connectorConfig: EISInstanceConfig = EISInstanceConfig(
    "http",
    "localhost",
    wiremockPort,
    uriStub,
    Headers("bearertokenhereGB"),
    CircuitBreakerConfig(
      3,
      1.seconds,
      2.seconds,
      3.seconds,
      1,
      0
    ),
    RetryConfig(
      1,
      1.second,
      2.seconds
    )
  )

  def noRetriesConnector = new MessageConnectorImpl("NoRetry", connectorConfig, TestHelpers.headerCarrierConfig, AhcWSClient(), NoRetries)
  def oneRetryConnector  = new MessageConnectorImpl("OneRetry", connectorConfig, TestHelpers.headerCarrierConfig, AhcWSClient(), OneRetry)

  lazy val connectorGen: Gen[() => MessageConnector] = Gen.oneOf(() => noRetriesConnector, () => oneRetryConnector)

  def source: Source[ByteString, _] = Source.single(ByteString.fromString("<test></test>"))

  "post" should {

    "add CustomProcessHost and X-Correlation-Id headers to messages for GB" in forAll(connectorGen) {
      connector =>
        server.resetAll() // Need to reset due to the forAll - it's technically the same test

        // Important note: while this test considers successes, as this connector has a retry function,
        // we have to ensure that any success result is not retried. To do this, we make the stub return
        // a 202 status the first time it is called, then we transition it into a state where it'll return
        // an error. As the retry algorithm should not attempt a retry on a 202, the stub should only be
        // called once - so a 500 should never be returned.
        //
        // If a 500 error is returned, this most likely means a retry happened, the first place to look
        // should be the code the determines if a result is successful.

        def stub(currentState: String, targetState: String, codeToReturn: Int) =
          server.stubFor(
            post(
              urlEqualTo(uriStub)
            )
              .inScenario("Standard Call")
              .whenScenarioStateIs(currentState)
              .withHeader(
                "X-Correlation-Id",
                matching(
                  "\\b[0-9a-f]{8}\\b-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-\\b[0-9a-f]{12}\\b"
                )
              )
              .withHeader("CustomProcessHost", equalTo("Digital"))
              .withHeader(HeaderNames.ACCEPT, equalTo("application/xml"))
              .willReturn(aResponse().withStatus(codeToReturn))
              .willSetStateTo(targetState)
          )

        val secondState = "should now fail"

        stub(Scenario.STARTED, secondState, ACCEPTED)
        stub(secondState, secondState, INTERNAL_SERVER_ERROR)

        val hc = HeaderCarrier()

        whenReady(connector().post(MessageSender("sender"), source, hc)) {
          _.isRight mustBe true
        }
    }

    "return a unit when post is successful" in forAll(connectorGen) {
      connector =>
        server.resetAll() // Need to reset due to the forAll - it's technically the same test

        def stub(currentState: String, targetState: String, codeToReturn: Int) =
          server.stubFor(
            post(
              urlEqualTo(uriStub)
            ).withHeader("Authorization", equalTo("Bearer bearertokenhereGB"))
              .withHeader(HeaderNames.ACCEPT, equalTo("application/xml"))
              .withHeader(
                "X-Correlation-Id",
                matching(
                  "\\b[0-9a-f]{8}\\b-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-\\b[0-9a-f]{12}\\b"
                )
              )
              .inScenario("Standard Call")
              .whenScenarioStateIs(currentState)
              .willReturn(aResponse().withStatus(codeToReturn))
              .willSetStateTo(targetState)
          )

        val secondState = "should now fail"

        stub(Scenario.STARTED, secondState, ACCEPTED)
        stub(secondState, secondState, INTERNAL_SERVER_ERROR)

        val hc = HeaderCarrier()

        whenReady(connector().post(MessageSender("sender"), source, hc)) {
          _.isRight mustBe true
        }
    }

    "return unit when post is successful on retry if there is an initial failure" in {
      def stub(currentState: String, targetState: String, codeToReturn: Int) =
        server.stubFor(
          post(
            urlEqualTo(uriStub)
          )
            .inScenario("Flaky Call")
            .whenScenarioStateIs(currentState)
            .willSetStateTo(targetState)
            .withHeader("Authorization", equalTo("Bearer bearertokenhereGB"))
            .withHeader(HeaderNames.ACCEPT, equalTo("application/xml"))
            .withHeader(
              "X-Correlation-Id",
              matching("\\b[0-9a-f]{8}\\b-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-\\b[0-9a-f]{12}\\b")
            )
            .willReturn(aResponse().withStatus(codeToReturn))
        )

      val secondState = "should now succeed"

      stub(Scenario.STARTED, secondState, INTERNAL_SERVER_ERROR)
      stub(secondState, secondState, ACCEPTED)

      val hc = HeaderCarrier()

      whenReady(oneRetryConnector.post(MessageSender("sender"), source, hc)) {
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

    "pass through error status codes" in forAll(errorCodes, connectorGen) {
      (statusCode, connector) =>
        server.resetAll() // Need to reset due to the forAll - it's technically the same test

        server.stubFor(
          post(
            urlEqualTo(uriStub)
          ).withHeader("Authorization", equalTo("Bearer bearertokenhereGB"))
            .withHeader(HeaderNames.ACCEPT, equalTo("application/xml"))
            .withHeader(
              "X-Correlation-Id",
              matching(
                "\\b[0-9a-f]{8}\\b-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-\\b[0-9a-f]{12}\\b"
              )
            )
            .willReturn(aResponse().withStatus(statusCode))
        )

        val hc = HeaderCarrier()

        whenReady(connector().post(MessageSender("sender"), source, hc)) {
          case Left(x) if x.isInstanceOf[RoutingError.Upstream] =>
            x.asInstanceOf[RoutingError.Upstream].upstreamErrorResponse.statusCode mustBe statusCode
          case x =>
            println(x)
            fail("Left was not a RoutingError.Upstream")
        }
    }
  }

  "handle exceptions by returning an HttpResponse with status code 500" in {
    val ws = mock[WSClient]
    val request = mock[WSRequest]
    val error = new RuntimeException("Simulated error")
    when(ws.url(anyString())).thenReturn(request)
    when(request.addHttpHeaders(ArgumentMatchers.any())).thenReturn(request)
    when(request.post(ArgumentMatchers.any[Source[ByteString, _]])(ArgumentMatchers.any())).thenReturn(Future.failed(error))

    val hc        = HeaderCarrier()
    val connector = new MessageConnectorImpl("Failure", connectorConfig, TestHelpers.headerCarrierConfig, ws, NoRetries)

    whenReady(connector.post(MessageSender("sender"), source, hc)) {
      case Left(x) if x.isInstanceOf[RoutingError.Unexpected] => x.asInstanceOf[RoutingError.Unexpected].cause mustBe Some(error)
      case _ => fail("Left was not a RoutingError.Unexpected")
    }
  }
}
