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
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import io.lemonlabs.uri.Url
import io.lemonlabs.uri.UrlPath
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.when
import org.scalacheck.Arbitrary.arbitrary
import org.scalatest.concurrent.IntegrationPatience
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import play.api.http.HeaderNames
import play.api.http.Status.INTERNAL_SERVER_ERROR
import play.api.http.Status.NO_CONTENT
import play.api.libs.json.JsSuccess
import play.api.libs.json.Json
import play.mvc.Http.MimeTypes
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.UpstreamErrorResponse
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.test.HttpClientV2Support
import uk.gov.hmrc.objectstore.client.ObjectSummaryWithMd5
import uk.gov.hmrc.transitmovementsrouter.it.base.TestActorSystem
import uk.gov.hmrc.transitmovementsrouter.config.AppConfig
import uk.gov.hmrc.transitmovementsrouter.controllers.errors.PresentationError
import uk.gov.hmrc.transitmovementsrouter.controllers.errors.StandardError
import uk.gov.hmrc.transitmovementsrouter.it.base.WiremockSuite
import uk.gov.hmrc.transitmovementsrouter.it.generators.ModelGenerators
import uk.gov.hmrc.transitmovementsrouter.models.ConversationId
import uk.gov.hmrc.transitmovementsrouter.models.MessageId
import uk.gov.hmrc.transitmovementsrouter.models.MovementId
import uk.gov.hmrc.transitmovementsrouter.models.errors.ErrorCode
import uk.gov.hmrc.transitmovementsrouter.models.errors.SDESError
import uk.gov.hmrc.transitmovementsrouter.models.sdes.FileMd5Checksum
import uk.gov.hmrc.transitmovementsrouter.models.sdes.FileName
import uk.gov.hmrc.transitmovementsrouter.models.sdes.FileSize
import uk.gov.hmrc.transitmovementsrouter.models.sdes.FileURL
import uk.gov.hmrc.transitmovementsrouter.utils.UUIDGenerator

import java.net.URL
import java.util.UUID
import scala.concurrent.ExecutionContext

class SDESConnectorSpec
    extends AnyFreeSpec
    with TestActorSystem
    with HttpClientV2Support
    with Matchers
    with WiremockSuite
    with ScalaFutures
    with MockitoSugar
    with IntegrationPatience
    with ModelGenerators
    with ScalaCheckDrivenPropertyChecks {

  object SingleUUIDGenerator extends UUIDGenerator {

    lazy val singleUUID: UUID = UUID.randomUUID()

    override def generateUUID(): UUID = singleUUID
  }

  val appConfig: AppConfig = mock[AppConfig]
  when(appConfig.sdesServiceBaseUrl).thenReturn(Url.parse("http://localhost:11111"))
  when(appConfig.objectStoreUrl).thenReturn("http://localhost")
  when(appConfig.sdesSrn).thenReturn("srn")
  when(appConfig.sdesClientId).thenReturn("clientId")
  when(appConfig.sdesInformationType).thenReturn("informationType")
  when(appConfig.sdesFileReadyUri).thenReturn(UrlPath(Seq("sdes-stub", "notification", "fileready")))

  implicit lazy val ec: ExecutionContext = materializer.executionContext

  implicit val hc: HeaderCarrier = HeaderCarrier()

  lazy val url = "/sdes-stub/notification/fileready"

  "POST /notification/fileready" - {

    "When SDES responds with NO_CONTENT, must returned a successful future with a Right" in forAll(
      arbitrary[MovementId],
      arbitrary[MessageId],
      arbitrary[ObjectSummaryWithMd5]
    ) {
      (movementId, messageId, objectSummaryWithMd5) =>
        server.resetAll()

        val sut: SDESConnector = new SDESConnectorImpl(httpClientV2, appConfig, SingleUUIDGenerator)

        val expectedHash = FileMd5Checksum.fromBase64(objectSummaryWithMd5.contentMd5)

        val expectedJson = Json.obj(
          "informationType" -> "informationType",
          "file" -> Json.obj(
            "recipientOrSender" -> "srn",
            "name"              -> objectSummaryWithMd5.location.fileName,
            "location"          -> s"http://localhost/${objectSummaryWithMd5.location.asUri}",
            "checksum" -> Json.obj(
              "value"     -> expectedHash.value,
              "algorithm" -> "md5"
            ),
            "size" -> objectSummaryWithMd5.contentLength,
            "properties" -> Json.arr(
              Json.obj("name" -> "x-conversation-id", "value" -> ConversationId(movementId, messageId).value.toString)
            )
          ),
          "audit" -> Json.obj(
            "correlationID" -> SingleUUIDGenerator.singleUUID.toString
          )
        )

        server.stubFor(
          post(
            urlEqualTo(url)
          ).withRequestBody(equalToJson(Json.stringify(expectedJson)))
            .withHeader(HeaderNames.CONTENT_TYPE, equalTo(MimeTypes.JSON))
            .withHeader("X-Client-Id", equalTo("clientId"))
            .willReturn(aResponse().withStatus(NO_CONTENT))
        )

        whenReady(
          sut.send(
            movementId,
            messageId,
            FileName(objectSummaryWithMd5.location),
            FileURL(objectSummaryWithMd5.location, "http://localhost"),
            FileMd5Checksum.fromBase64(objectSummaryWithMd5.contentMd5),
            FileSize(objectSummaryWithMd5.contentLength)
          )
        ) {
          result =>
            result mustBe Right(())
        }
    }

    "On an upstream internal server error, must returned a successful future with a Left of Unexpected Error" in forAll(
      arbitrary[MovementId],
      arbitrary[MessageId],
      arbitrary[ObjectSummaryWithMd5]
    ) {
      (movementId, messageId, objectSummaryWithMd5) =>
        server.resetAll()

        val sut: SDESConnector = new SDESConnectorImpl(httpClientV2, appConfig, SingleUUIDGenerator)

        val expectedHash = FileMd5Checksum.fromBase64(objectSummaryWithMd5.contentMd5)

        val expectedJson = Json.obj(
          "informationType" -> "informationType",
          "file" -> Json.obj(
            "recipientOrSender" -> "srn",
            "name"              -> objectSummaryWithMd5.location.fileName,
            "location"          -> s"http://localhost/${objectSummaryWithMd5.location.asUri}",
            "checksum" -> Json.obj(
              "value"     -> expectedHash.value,
              "algorithm" -> "md5"
            ),
            "size" -> objectSummaryWithMd5.contentLength,
            "properties" -> Json.arr(
              Json.obj("name" -> "x-conversation-id", "value" -> ConversationId(movementId, messageId).value.toString)
            )
          ),
          "audit" -> Json.obj(
            "correlationID" -> SingleUUIDGenerator.singleUUID.toString
          )
        )

        server.stubFor(
          post(
            urlEqualTo(url)
          ).withRequestBody(equalToJson(Json.stringify(expectedJson)))
            .withHeader(HeaderNames.CONTENT_TYPE, equalTo(MimeTypes.JSON))
            .withHeader("X-Client-Id", equalTo("clientId"))
            .willReturn(
              aResponse()
                .withStatus(INTERNAL_SERVER_ERROR)
                .withBody(
                  Json.stringify(Json.toJson(PresentationError.internalServiceError()))
                )
            )
        )

        val future =
          sut.send(
            movementId,
            messageId,
            FileName(objectSummaryWithMd5.location),
            FileURL(objectSummaryWithMd5.location, "http://localhost"),
            FileMd5Checksum.fromBase64(objectSummaryWithMd5.contentMd5),
            FileSize(objectSummaryWithMd5.contentLength)
          )

        whenReady(future) {
          case Left(SDESError.UnexpectedError(Some(response: UpstreamErrorResponse))) =>
            response.statusCode mustBe INTERNAL_SERVER_ERROR
            Json.parse(response.message).validate[StandardError] mustBe JsSuccess(StandardError("Internal server error", ErrorCode.InternalServerError))
          case x => fail(s"Expected an unexpected error, got $x")
        }
    }

    "On an exception, must returned a successful future with a Left of Unexpected Error" in forAll(
      arbitrary[MovementId],
      arbitrary[MessageId],
      arbitrary[ObjectSummaryWithMd5]
    ) {
      (movementId, messageId, objectSummaryWithMd5) =>
        server.resetAll()

        val mockHttpClientV2 = mock[HttpClientV2]
        when(mockHttpClientV2.post(any[URL])(any[HeaderCarrier])).thenReturn(new FakeRequestBuilder)

        val sut: SDESConnector = new SDESConnectorImpl(mockHttpClientV2, appConfig, SingleUUIDGenerator)
        val future = sut.send(
          movementId,
          messageId,
          FileName(objectSummaryWithMd5.location),
          FileURL(objectSummaryWithMd5.location, "http://localhost"),
          FileMd5Checksum.fromBase64(objectSummaryWithMd5.contentMd5),
          FileSize(objectSummaryWithMd5.contentLength)
        )

        whenReady(future) {
          case Left(SDESError.UnexpectedError(Some(_: RuntimeException))) => succeed
          case x                                                          => fail(s"Expected an unexpected error with a runtime exception, got $x")
        }
    }

  }

}
