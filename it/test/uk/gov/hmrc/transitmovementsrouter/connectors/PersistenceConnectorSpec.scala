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
import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.stubbing.StubMapping
import io.lemonlabs.uri.QueryString
import io.lemonlabs.uri.Url
import org.mockito.Mockito.when
import org.scalacheck.Gen
import org.scalatest.concurrent.IntegrationPatience
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import play.api.http.HeaderNames
import play.api.http.Status.BAD_REQUEST
import play.api.http.Status.INTERNAL_SERVER_ERROR
import play.api.http.Status.NOT_FOUND
import play.api.http.Status.OK
import play.api.libs.json.Json
import play.mvc.Http.MimeTypes
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.test.HttpClientV2Support
import uk.gov.hmrc.transitmovementsrouter.config.AppConfig
import uk.gov.hmrc.transitmovementsrouter.connectors.PersistenceConnectorImpl
import uk.gov.hmrc.transitmovementsrouter.it.base.WiremockSuite
import uk.gov.hmrc.transitmovementsrouter.it.generators.ModelGenerators
import uk.gov.hmrc.transitmovementsrouter.models.MessageType.DeclarationAmendment
import uk.gov.hmrc.transitmovementsrouter.models._
import uk.gov.hmrc.transitmovementsrouter.models.errors.PersistenceError.MessageNotFound
import uk.gov.hmrc.transitmovementsrouter.models.errors.PersistenceError.MovementNotFound
import uk.gov.hmrc.transitmovementsrouter.models.errors.PersistenceError.Unexpected
import uk.gov.hmrc.transitmovementsrouter.models.requests.MessageUpdate
import uk.gov.hmrc.transitmovementsrouter.services.EISMessageTransformersImpl

import scala.concurrent.ExecutionContext.Implicits.global

class PersistenceConnectorSpec
    extends AnyWordSpec
    with HttpClientV2Support
    with Matchers
    with WiremockSuite
    with ScalaFutures
    with MockitoSugar
    with IntegrationPatience
    with ModelGenerators
    with ScalaCheckPropertyChecks {

  val movementId: MovementId = arbitraryMovementId.arbitrary.sample.get
  val messageId: MessageId   = arbitraryMessageId.arbitrary.sample.get
  val eoriNumber: EoriNumber = arbitraryEoriNumber.arbitrary.sample.get
  val clientId: ClientId     = arbitraryClientId.arbitrary.sample.get
  val objectStoreURI         = s"common-transit-convention-traders/movements/${movementId.value}/${ConversationId(movementId, messageId).value.toString}.xml"

  val uriPersistence: String = Url(
    path = s"/transit-movements/traders/movements/${movementId.value}/messages",
    query = QueryString.fromPairs("triggerId" -> messageId.value)
  ).toStringRaw

  val uriStatus: String = Url(
    path = s"/transit-movements/traders/movements/${movementId.value}/messages/${messageId.value}"
  ).toStringRaw

  val token: String = Gen.alphaNumStr.sample.get

  implicit val appConfig: AppConfig = mock[AppConfig]
  when(appConfig.internalAuthToken).thenReturn(token)
  when(appConfig.persistenceServiceBaseUrl).thenAnswer {
    _ =>
      Url.parse(server.baseUrl())
  }

  implicit val hc: HeaderCarrier = HeaderCarrier()
  lazy val connector             = new PersistenceConnectorImpl(httpClientV2)

  val errorCodes: Gen[Int] = Gen.oneOf(
    Seq(
      BAD_REQUEST,
      INTERNAL_SERVER_ERROR,
      NOT_FOUND
    )
  )

  def source: Source[ByteString, _] = Source.single(ByteString.fromString("<TraderChannelResponse><CC013C></CC013C></TraderChannelResponse>"))

  def stubForPostBody(codeToReturn: Int, body: Option[String] = None): StubMapping = server.stubFor(
    post(
      urlEqualTo(uriPersistence)
    ).withHeader("X-Message-Type", equalTo(MessageType.DeclarationAmendment.code))
      .withHeader(HeaderNames.AUTHORIZATION, equalTo(token))
      .withHeader(HeaderNames.CONTENT_TYPE, equalTo(MimeTypes.XML))
      .withRequestBody(equalToXml("<TraderChannelResponse><CC013C></CC013C></TraderChannelResponse>"))
      .willReturn(
        if (body.isEmpty) aResponse().withStatus(codeToReturn)
        else aResponse().withStatus(codeToReturn).withBody(body.get)
      )
  )

  def stubForPatchMessageStatus(codeToReturn: Int): StubMapping = server.stubFor(
    patch(
      urlEqualTo(uriStatus)
    )
      .withHeader(HeaderNames.AUTHORIZATION, equalTo(token))
      .withHeader(HeaderNames.CONTENT_TYPE, equalTo(MimeTypes.JSON))
      .withRequestBody(
        equalToJson(
          Json
            .obj(
              "status" -> MessageStatus.Success.toString
            )
            .toString()
        )
      )
      .willReturn(
        aResponse().withStatus(codeToReturn)
      )
  )

  "post" should {
    "return messageId when post is successful" in {

      val body = Json.obj("messageId" -> messageId.value, "eori" -> eoriNumber, "clientId" -> Option(clientId)).toString()

      stubForPostBody(OK, Some(body))
      whenReady(connector.postBody(movementId, messageId, DeclarationAmendment, source).value) {
        x =>
          x.isRight mustBe true
          x mustBe Right(PersistenceResponse(messageId, eoriNumber, Option(clientId), isTransitional = true))
      }
    }

    "return a PersistenceError when unsuccessful" in forAll(errorCodes) {
      statusCode =>
        server.resetAll()

        stubForPostBody(statusCode)

        whenReady(connector.postBody(movementId, messageId, DeclarationAmendment, source).value) {
          x =>
            x.isLeft mustBe true

            statusCode match {
              case BAD_REQUEST           => x mustBe a[Left[Unexpected, _]]
              case NOT_FOUND             => x mustBe a[Left[MovementNotFound, _]]
              case INTERNAL_SERVER_ERROR => x mustBe a[Left[Unexpected, _]]
              case _                     => fail()
            }

        }
    }

    "return Unexpected(throwable) when NonFatal exception is thrown" in {
      server.resetAll()

      stubForPostBody(OK)

      val failingSource = Source.single(ByteString.fromString("<abc>asdadsadads")).via(new EISMessageTransformersImpl().unwrap)

      whenReady(connector.postBody(movementId, messageId, DeclarationAmendment, failingSource).value) {
        res =>
          res mustBe a[Left[Unexpected, _]]
          res.left.toOption.get.asInstanceOf[Unexpected].thr.isDefined
      }
    }

  }

  "patch for update message status" should {
    "return 200 when it is successful" in {

      stubForPatchMessageStatus(OK)

      whenReady(connector.patchMessageStatus(movementId, messageId, MessageUpdate(MessageStatus.Success)).value) {
        x =>
          x.isRight mustBe true
          x mustBe Right(())
      }
    }

    "return a PersistenceError when unsuccessful" in forAll(errorCodes) {
      statusCode =>
        server.resetAll()

        stubForPatchMessageStatus(statusCode)

        whenReady(connector.patchMessageStatus(movementId, messageId, MessageUpdate(MessageStatus.Success)).value) {
          case Left(error) =>
            (statusCode: @unchecked) match {
              case BAD_REQUEST           => error mustBe a[Unexpected]
              case NOT_FOUND             => error mustBe a[MessageNotFound]
              case INTERNAL_SERVER_ERROR => error mustBe a[Unexpected]
            }
          case Right(_) => fail("An error was expected")
        }
    }
  }
}
