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
import io.lemonlabs.uri.Url
import org.apache.pekko.stream.scaladsl.Source
import org.mockito.Mockito.when
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Arbitrary
import org.scalacheck.Gen
import org.scalatest.concurrent.IntegrationPatience
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.exceptions.TestFailedException
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import play.api.http.HeaderNames
import play.api.http.Status.ACCEPTED
import play.api.http.Status.BAD_REQUEST
import play.api.http.Status.INTERNAL_SERVER_ERROR
import play.api.libs.json.JsObject
import play.api.libs.json.JsValue
import play.api.libs.json.Json
import play.mvc.Http.MimeTypes
import uk.gov.hmrc.transitmovementsrouter.it.base.TestMetrics
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.UpstreamErrorResponse
import uk.gov.hmrc.http.test.HttpClientV2Support
import uk.gov.hmrc.transitmovementsrouter.config.AppConfig
import uk.gov.hmrc.transitmovementsrouter.it.base.WiremockSuite
import uk.gov.hmrc.transitmovementsrouter.it.generators.ModelGenerators
import uk.gov.hmrc.transitmovementsrouter.models._
import uk.gov.hmrc.transitmovementsrouter.models.requests.Details
import uk.gov.hmrc.transitmovementsrouter.models.requests.Metadata
import uk.gov.hmrc.transitmovementsrouter.utils.RouterHeaderNames.CLIENT_ID

import scala.concurrent.ExecutionContext.Implicits.global

class AuditingConnectorSpec
    extends AnyFreeSpec
    with Matchers
    with MockitoSugar
    with ScalaFutures
    with WiremockSuite
    with IntegrationPatience
    with HttpClientV2Support
    with ScalaCheckDrivenPropertyChecks
    with ModelGenerators {

  val token: String                     = Gen.alphaNumStr.sample.get
  implicit val mockAppConfig: AppConfig = mock[AppConfig]
  when(mockAppConfig.internalAuthToken).thenReturn(token)
  when(mockAppConfig.auditingUrl).thenAnswer(
    _ => Url.parse(server.baseUrl())
  ) // using thenAnswer for lazy semantics

  lazy val sut                        = new AuditingConnectorImpl(httpClientV2, new TestMetrics)
  def targetUrl(auditType: AuditType) = s"/transit-movements-auditing/audit/${auditType.name}"

  lazy val contentSize = 49999L

  "For auditing message Type event" - {
    "when sending an audit message" - Seq(MimeTypes.XML, MimeTypes.JSON).foreach {
      contentType =>
        s"when the content-type equals $contentType" - {
          "return a successful future if the audit message was accepted with all headers" in forAll(
            arbitrary[EoriNumber],
            arbitrary[MovementId],
            arbitrary[MessageId],
            arbitrary[MessageType],
            arbitrary[MovementType]
          ) {
            (eori, movementId, messageId, messageType, movementType) =>
              server.stubFor(
                post(
                  urlEqualTo(targetUrl(AuditType.AmendmentAcceptance))
                )
                  .withHeader(HeaderNames.AUTHORIZATION, equalTo(token))
                  .withHeader(HeaderNames.CONTENT_TYPE, equalTo(contentType))
                  .withHeader("X-ContentLength", equalTo(contentSize.toString))
                  .withHeader("X-Audit-Meta-Movement-Id", equalTo(movementId.value))
                  .withHeader("X-Audit-Meta-Message-Id", equalTo(messageId.value))
                  .withHeader("X-Audit-Meta-EORI", equalTo(eori.value))
                  .withHeader("X-Audit-Meta-Path", equalTo("/customs/transits/movements"))
                  .withHeader("X-Audit-Meta-Message-Type", equalTo(messageType.code))
                  .withHeader("X-Audit-Meta-Movement-Type", equalTo(movementType.value))
                  .withHeader("X-Audit-Source", equalTo("transit-movements-router"))
                  .willReturn(aResponse().withStatus(ACCEPTED))
              )

              implicit val hc: HeaderCarrier = HeaderCarrier(otherHeaders = Seq("path" -> "/customs/transits/movements"))
              // when we call the audit service
              val future = sut.postMessageType(
                AuditType.AmendmentAcceptance,
                contentType,
                contentSize,
                Source.empty,
                Some(movementId),
                Some(messageId),
                Some(eori),
                Some(movementType),
                Some(messageType),
                isTransitional = false
              )

              // then the future should be ready
              whenReady(future) {
                _ =>
              }
          }

          "return a successful future if the audit message was accepted with movementId, messageId & eoriNumber Optional header value" in forAll(
            arbitrary[EoriNumber],
            arbitrary[MovementId],
            arbitrary[MessageId]
          ) {
            (eori, movementId, messageId) =>
              server.stubFor(
                post(
                  urlEqualTo(targetUrl(AuditType.AmendmentAcceptance))
                )
                  .withHeader(HeaderNames.AUTHORIZATION, equalTo(token))
                  .withHeader(HeaderNames.CONTENT_TYPE, equalTo(contentType))
                  .withHeader("X-ContentLength", equalTo(contentSize.toString))
                  .withHeader("X-Audit-Meta-Movement-Id", equalTo(movementId.value))
                  .withHeader("X-Audit-Meta-Message-Id", equalTo(messageId.value))
                  .withHeader("X-Audit-Meta-EORI", equalTo(eori.value))
                  .withHeader("X-Audit-Meta-Path", equalTo("/customs/transits/movements"))
                  .withHeader("X-Audit-Source", equalTo("transit-movements-router"))
                  .willReturn(aResponse().withStatus(ACCEPTED))
              )

              implicit val hc: HeaderCarrier = HeaderCarrier(otherHeaders = Seq("path" -> "/customs/transits/movements"))
              // when we call the audit service
              val future = sut.postMessageType(
                AuditType.AmendmentAcceptance,
                contentType,
                contentSize,
                Source.empty,
                Some(movementId),
                Some(messageId),
                Some(eori),
                None,
                None,
                isTransitional = false
              )

              // then the future should be ready
              whenReady(future) {
                _ =>
              }
          }

          "return a successful future if the audit message was accepted with movementType & messageType Optional header value" in forAll(
            arbitrary[MessageType],
            arbitrary[MovementType]
          ) {
            (messageType, movementType) =>
              server.stubFor(
                post(
                  urlEqualTo(targetUrl(AuditType.AmendmentAcceptance))
                )
                  .withHeader(HeaderNames.AUTHORIZATION, equalTo(token))
                  .withHeader(HeaderNames.CONTENT_TYPE, equalTo(contentType))
                  .withHeader("X-ContentLength", equalTo(contentSize.toString))
                  .withHeader("X-Audit-Meta-Message-Type", equalTo(messageType.code))
                  .withHeader("X-Audit-Meta-Movement-Type", equalTo(movementType.value))
                  .withHeader("X-Audit-Meta-Path", equalTo("/customs/transits/movements"))
                  .withHeader("X-Audit-Source", equalTo("transit-movements-router"))
                  .willReturn(aResponse().withStatus(ACCEPTED))
              )

              implicit val hc: HeaderCarrier = HeaderCarrier(otherHeaders = Seq("path" -> "/customs/transits/movements"))
              // when we call the audit service
              val future = sut.postMessageType(
                AuditType.AmendmentAcceptance,
                contentType,
                contentSize,
                Source.empty,
                None,
                None,
                None,
                Some(movementType),
                Some(messageType),
                isTransitional = false
              )

              // then the future should be ready
              whenReady(future) {
                _ =>
              }
          }

          "return a successful future if the audit message was accepted without any Optional header value" in {
            server.stubFor(
              post(
                urlEqualTo(targetUrl(AuditType.AmendmentAcceptance))
              )
                .withHeader(HeaderNames.AUTHORIZATION, equalTo(token))
                .withHeader(HeaderNames.CONTENT_TYPE, equalTo(contentType))
                .withHeader("X-ContentLength", equalTo(contentSize.toString))
                .withHeader("X-Audit-Meta-Path", equalTo("/customs/transits/movements"))
                .withHeader("X-Audit-Source", equalTo("transit-movements-router"))
                .willReturn(aResponse().withStatus(ACCEPTED))
            )

            implicit val hc: HeaderCarrier = HeaderCarrier(otherHeaders = Seq("path" -> "/customs/transits/movements"))
            // when we call the audit service
            val future = sut.postMessageType(
              AuditType.AmendmentAcceptance,
              contentType,
              contentSize,
              Source.empty,
              None,
              None,
              None,
              None,
              None,
              isTransitional = false
            )

            // then the future should be ready
            whenReady(future) {
              _ =>
            }
          }

          "return a failed future if the audit message was not accepted" - Seq(BAD_REQUEST, INTERNAL_SERVER_ERROR).foreach {
            statusCode =>
              s"when a $statusCode is returned" in forAll(
                arbitrary[EoriNumber],
                arbitrary[MovementId],
                arbitrary[MessageId],
                arbitrary[MessageType],
                arbitrary[MovementType]
              ) {
                (eori, movementId, messageId, messageType, movementType) =>
                  server.resetAll()
                  server.stubFor(
                    post(
                      urlEqualTo(targetUrl(AuditType.AmendmentAcceptance))
                    )
                      .withHeader(HeaderNames.AUTHORIZATION, equalTo(token))
                      .withHeader(HeaderNames.CONTENT_TYPE, equalTo(contentType))
                      .withHeader("X-ContentLength", equalTo(contentSize.toString))
                      .withHeader("X-Audit-Meta-Movement-Id", equalTo(movementId.value))
                      .withHeader("X-Audit-Meta-Message-Id", equalTo(messageId.value))
                      .withHeader("X-Audit-Meta-EORI", equalTo(eori.value))
                      .withHeader("X-Audit-Meta-Path", equalTo("/customs/transits/movements"))
                      .withHeader("X-Audit-Meta-Message-Type", equalTo(messageType.code))
                      .withHeader("X-Audit-Meta-Movement-Type", equalTo(movementType.value))
                      .withHeader("X-Audit-Source", equalTo("transit-movements-router"))
                      .willReturn(aResponse().withStatus(statusCode))
                  )

                  implicit val hc: HeaderCarrier = HeaderCarrier(otherHeaders = Seq("path" -> "/customs/transits/movements"))
                  // when we call the audit service
                  val future = sut.postMessageType(
                    AuditType.AmendmentAcceptance,
                    contentType,
                    contentSize,
                    Source.empty,
                    Some(movementId),
                    Some(messageId),
                    Some(eori),
                    Some(movementType),
                    Some(messageType),
                    isTransitional = false
                  )

                  val result = future
                    .map(
                      _ => fail("A success was registered when it should have been a failure.")
                    )
                    .recover {
                      // backticks for stable identifier
                      case UpstreamErrorResponse(_, `statusCode`, _, _) => ()
                      case x: TestFailedException                       => x
                      case x                                            => fail(s"An unexpected exception was thrown: ${x.getClass.getSimpleName}, ${x.getMessage}")
                    }

                  // then the future should be ready
                  whenReady(result) {
                    _ =>
                  }
              }
          }

        }
    }
  }

  "For auditing status Type event" - {
    implicit val jsValueArbitrary: Arbitrary[JsValue] = Arbitrary(Gen.const(Json.obj("code" -> "BUSINESS_VALIDATION_ERROR", "message" -> "Expected NTA.GB")))
    "when sending an audit message return a successful future if the audit message was accepted" in forAll(
      Gen.option(arbitrary[EoriNumber]),
      Gen.option(arbitrary[MovementId]),
      Gen.option(arbitrary[MessageId]),
      Gen.option(arbitrary[MessageType]),
      Gen.option(arbitrary[MovementType]),
      Gen.option(arbitrary[JsValue])
    ) {
      (eori, movementId, messageId, messageType, movementType, payload) =>
        server.stubFor(
          post(
            urlEqualTo(targetUrl(AuditType.AmendmentAcceptance))
          )
            .withHeader(HeaderNames.AUTHORIZATION, equalTo(token))
            .withHeader(CLIENT_ID, equalTo("1234"))
            .withHeader(HeaderNames.CONTENT_TYPE, equalTo("application/json"))
            .withHeader("X-Audit-Source", equalTo("transit-movements-router"))
            .withRequestBody(
              equalToJson(
                Json.stringify(
                  Json.toJson(
                    Details(Metadata("/customs/transits/movements", movementId, messageId, eori, movementType, messageType), payload.map(_.as[JsObject]))
                  )
                )
              )
            )
            .willReturn(aResponse().withStatus(ACCEPTED))
        )

        implicit val hc: HeaderCarrier = HeaderCarrier(otherHeaders = Seq("path" -> "/customs/transits/movements"))
        // when we call the audit service
        val future = sut.postStatus(
          AuditType.AmendmentAcceptance,
          payload,
          movementId,
          messageId,
          eori,
          movementType,
          messageType,
          Some(ClientId("1234"))
        )

        // then the future should be ready
        whenReady(future) {
          _ =>
        }
    }

    "return a failed future if the audit message was not accepted" - Seq(BAD_REQUEST, INTERNAL_SERVER_ERROR).foreach {
      statusCode =>
        s"when a $statusCode is returned" in forAll(
          Gen.option(arbitrary[EoriNumber]),
          Gen.option(arbitrary[MovementId]),
          Gen.option(arbitrary[MessageId]),
          Gen.option(arbitrary[MessageType]),
          Gen.option(arbitrary[MovementType]),
          Gen.option(arbitrary[JsValue])
        ) {
          (eori, movementId, messageId, messageType, movementType, payload) =>
            server.stubFor(
              post(
                urlEqualTo(targetUrl(AuditType.AmendmentAcceptance))
              )
                .withHeader(HeaderNames.AUTHORIZATION, equalTo(token))
                .withHeader(CLIENT_ID, equalTo("1234"))
                .withHeader(HeaderNames.CONTENT_TYPE, equalTo("application/json"))
                .withHeader("X-Audit-Source", equalTo("transit-movements-router"))
                .withRequestBody(
                  equalToJson(
                    Json.stringify(
                      Json.toJson(
                        Details(Metadata("/customs/transits/movements", movementId, messageId, eori, movementType, messageType), payload.map(_.as[JsObject]))
                      )
                    )
                  )
                )
                .willReturn(aResponse().withStatus(statusCode))
            )

            implicit val hc: HeaderCarrier = HeaderCarrier(otherHeaders = Seq("path" -> "/customs/transits/movements"))
            // when we call the audit service
            val future = sut.postStatus(
              AuditType.AmendmentAcceptance,
              payload,
              movementId,
              messageId,
              eori,
              movementType,
              messageType,
              Some(ClientId("1234"))
            )

            val result = future
              .map(
                _ => fail("A success was registered when it should have been a failure.")
              )
              .recover {
                // backticks for stable identifier
                case UpstreamErrorResponse(_, `statusCode`, _, _) => ()
                case x: TestFailedException                       => x
                case x                                            => fail(s"An unexpected exception was thrown: ${x.getClass.getSimpleName}, ${x.getMessage}")
              }

            // then the future should be ready
            whenReady(result) {
              _ =>
            }
        }
    }

  }

}
