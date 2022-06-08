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

import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.stubbing.Scenario
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.when
import org.scalacheck.Gen
import org.scalatest.concurrent.IntegrationPatience
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.must.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import play.api.http.HeaderNames
import play.api.inject.bind
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.ws.WSClient
import play.api.libs.ws.WSRequest
import play.api.test.Helpers._
import retry.RetryPolicies
import retry.RetryPolicy
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.transitmovementsrouter.base.TestActorSystem
import uk.gov.hmrc.transitmovementsrouter.config.AppConfig
import uk.gov.hmrc.transitmovementsrouter.config.RetryConfig
import uk.gov.hmrc.transitmovementsrouter.models.MessageSender

import scala.concurrent.ExecutionContext
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

  lazy val appWithNoRetries: GuiceApplicationBuilder =
    appBuilder.bindings(bind[Retries].toInstance(NoRetries))

  lazy val appWithOneRetry: GuiceApplicationBuilder =
    appBuilder.bindings(bind[Retries].toInstance(OneRetry))

  lazy val appBuilderGen: Gen[GuiceApplicationBuilder] =
    Gen.oneOf(appWithOneRetry, appWithNoRetries)

  lazy val source: Source[ByteString, _] = Source.single(ByteString.fromString("<test></test>"))

  "post" should {

    "add CustomProcessHost and X-Correlation-Id headers to messages for GB" in forAll(
      appBuilderGen
    ) {
      appBuilder =>
        server.resetAll()
        val app = appBuilder.build()

        running(app) {

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
                urlEqualTo("/transit-movements-eis-stub/movements/messages")
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

          val connector = app.injector.instanceOf[MessageConnector]

          val secondState = "should now fail"

          stub(Scenario.STARTED, secondState, ACCEPTED)
          stub(secondState, secondState, INTERNAL_SERVER_ERROR)

          val hc = HeaderCarrier()

          whenReady(connector.post(MessageSender("sender"), source, hc)) {
            _.isRight mustBe true
          }
        }
    }

    "return a unit when post is successful" in forAll(appBuilderGen) {
      appBuilder =>
        server.resetAll()
        val app = appBuilder.build()

        running(app) {
          def stub(currentState: String, targetState: String, codeToReturn: Int) =
            server.stubFor(
              post(
                urlEqualTo("/transit-movements-eis-stub/movements/messages")
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

          val connector = app.injector.instanceOf[MessageConnector]

          val secondState = "should now fail"

          stub(Scenario.STARTED, secondState, ACCEPTED)
          stub(secondState, secondState, INTERNAL_SERVER_ERROR)

          val hc = HeaderCarrier()

          whenReady(connector.post(MessageSender("sender"), source, hc)) {
            _.isRight mustBe true
          }
        }
    }

    "return unit when post is successful on retry if there is an initial failure" in {
      val app = appWithOneRetry.build()

      running(app) {
        def stub(currentState: String, targetState: String, codeToReturn: Int) =
          server.stubFor(
            post(
              urlEqualTo("/transits-movements-trader-at-departure-stub/movements/departures/gb")
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

        val connector = app.injector.instanceOf[MessageConnector]

        val secondState = "should now succeed"

        stub(Scenario.STARTED, secondState, INTERNAL_SERVER_ERROR)
        stub(secondState, secondState, ACCEPTED)

        val hc = HeaderCarrier()

        whenReady(connector.post(MessageSender("sender"), source, hc)) {
          _.isRight mustBe true
        }
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

    "pass through error status codes" in forAll(errorCodes, appBuilderGen) {
      (statusCode, appBuilder) =>
        val app = appBuilder.build()

        running(app) {
          val connector = app.injector.instanceOf[MessageConnector]

          server.stubFor(
            post(
              urlEqualTo("/transits-movements-trader-at-departure-stub/movements/departures/gb")
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

          whenReady(connector.post(MessageSender("sender"), source, hc)) {
            result =>
              result.left.get.statusCode mustBe statusCode
          }
        }
    }

    "handle exceptions by returning an HttpResponse with status code 500" in forAll(appBuilderGen) {
      appBuilder =>
        val app = appBuilder.build()

        running(app) {
          implicit val ec           = app.injector.instanceOf[ExecutionContext]
          implicit val materializer = app.injector.instanceOf[Materializer]
          val appConfig             = app.injector.instanceOf[AppConfig]
          val ws                    = mock[WSClient]

          val request = mock[WSRequest]

          when(ws.url(anyString())).thenThrow(new RuntimeException("Simulated error"))

          val connector = new MessageConnectorImpl("GB", appConfig.eisGb, appConfig.headerCarrierConfig, ws, NoRetries)
          val hc        = HeaderCarrier()

          whenReady(connector.post(MessageSender("sender"), source, hc)) {
            result =>
              result.left.get.statusCode mustBe INTERNAL_SERVER_ERROR
          }
        }
    }
  }
}
