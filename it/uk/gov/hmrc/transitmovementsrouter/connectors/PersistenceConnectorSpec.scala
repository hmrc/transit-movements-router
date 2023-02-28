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

import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
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
import play.api.http.Status.BAD_REQUEST
import play.api.http.Status.INTERNAL_SERVER_ERROR
import play.api.http.Status.NOT_FOUND
import play.api.http.Status.OK
import play.api.http.HeaderNames
import play.api.http.MimeTypes
import play.api.libs.json.Json
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.test.HttpClientV2Support
import uk.gov.hmrc.transitmovementsrouter.config.AppConfig
import uk.gov.hmrc.transitmovementsrouter.it.base.WiremockSuite
import uk.gov.hmrc.transitmovementsrouter.models.MessageType.DeclarationAmendment
import uk.gov.hmrc.transitmovementsrouter.models.errors.PersistenceError.MovementNotFound
import uk.gov.hmrc.transitmovementsrouter.models.errors.PersistenceError.Unexpected
import uk.gov.hmrc.transitmovementsrouter.models._
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
    with ScalaCheckPropertyChecks {

  val movementId = MovementId("ABC")
  val messageId  = MessageId("XYZ")

  val uriPersistence = Url(
    path = s"/transit-movements/traders/movements/${movementId.value}/messages",
    query = QueryString.fromPairs("triggerId" -> messageId.value)
  ).toStringRaw

  val appConfig = mock[AppConfig]

  when(appConfig.persistenceServiceBaseUrl).thenAnswer {
    _ =>
      Url.parse(server.baseUrl())
  }

  lazy val connector = new PersistenceConnectorImpl(httpClientV2, appConfig)

  val errorCodes = Gen.oneOf(
    Seq(
      BAD_REQUEST,
      INTERNAL_SERVER_ERROR,
      NOT_FOUND
    )
  )

  def source: Source[ByteString, _] = Source.single(ByteString.fromString("<TraderChannelResponse><CC013C><CC013C></TraderChannelResponse>"))

  def stub(codeToReturn: Int, body: Option[String] = None) = server.stubFor(
    post(
      urlEqualTo(uriPersistence)
    )
      .willReturn(
        if (body.isEmpty) aResponse().withStatus(codeToReturn)
        else aResponse().withStatus(codeToReturn).withBody(body.get)
      )
  )

  "post" should {
    "return messageId when post is successful" in {

      val body = Json.obj("messageId" -> messageId.value).toString()

      stub(OK, Some(body))
      implicit val hc: HeaderCarrier =
        HeaderCarrier(extraHeaders = Seq("X-Message-Type" -> MessageType.DeclarationAmendment.code, HeaderNames.CONTENT_TYPE -> MimeTypes.XML))
      whenReady(connector.postBody(movementId, messageId, DeclarationAmendment, source).value) {
        x =>
          x.isRight mustBe true
          x mustBe Right(PersistenceResponse(messageId))
      }
    }

    "return a PersistenceError when unsuccessful" in forAll(errorCodes) {
      statusCode =>
        server.resetAll()

        stub(statusCode)
        implicit val hc: HeaderCarrier =
          HeaderCarrier(extraHeaders = Seq("X-Message-Type" -> MessageType.DeclarationAmendment.code, HeaderNames.CONTENT_TYPE -> MimeTypes.XML))
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

      stub(OK)

      val failingSource = Source.single(ByteString.fromString("<abc>asdadsadads")).via(new EISMessageTransformersImpl().unwrap)
      implicit val hc: HeaderCarrier =
        HeaderCarrier(extraHeaders = Seq("X-Message-Type" -> MessageType.DeclarationAmendment.code, HeaderNames.CONTENT_TYPE -> MimeTypes.XML))
      whenReady(connector.postBody(movementId, messageId, DeclarationAmendment, failingSource).value) {
        res =>
          res mustBe a[Left[Unexpected, _]]
          res.left.toOption.get.asInstanceOf[Unexpected].thr.isDefined
      }
    }

  }

  "post for Large Message" should {
    "return messageId when post is successful" in {
      val body           = Json.obj("messageId" -> messageId.value).toString()
      val objectStoreURI = s"common-transit-convention-traders/movements/$movementId/x-conversion-id.xml"
      stub(OK, Some(body))
      implicit val hc: HeaderCarrier = HeaderCarrier(extraHeaders =
        Seq("X-Message-Type" -> MessageType.DeclarationAmendment.code, "X-Object-Store-Uri" -> ObjectStoreURI(objectStoreURI).value)
      )
      whenReady(connector.postObjectStoreUri(movementId, messageId, DeclarationAmendment, ObjectStoreURI(objectStoreURI)).value) {
        x =>
          x.isRight mustBe true
          x mustBe Right(PersistenceResponse(messageId))
      }
    }

    "return a PersistenceError when unsuccessful" in forAll(errorCodes) {
      statusCode =>
        server.resetAll()
        val objectStoreURI = s"common-transit-convention-traders/movements/$movementId/x-conversion-id.xml"
        stub(statusCode)
        implicit val hc: HeaderCarrier = HeaderCarrier(extraHeaders =
          Seq("X-Message-Type" -> MessageType.DeclarationAmendment.code, "X-Object-Store-Uri" -> ObjectStoreURI(objectStoreURI).value)
        )
        whenReady(connector.postObjectStoreUri(movementId, messageId, DeclarationAmendment, ObjectStoreURI(objectStoreURI)).value) {
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
      val objectStoreURI = s"common-transit-convention-traders/movements/$movementId/x-conversion-id.xml"
      stub(OK)
      implicit val hc: HeaderCarrier = HeaderCarrier(extraHeaders =
        Seq("X-Message-Type" -> MessageType.DeclarationAmendment.code, "X-Object-Store-Uri" -> ObjectStoreURI(objectStoreURI).value)
      )
      whenReady(connector.postObjectStoreUri(movementId, messageId, DeclarationAmendment, ObjectStoreURI(objectStoreURI)).value) {
        res =>
          res mustBe a[Left[Unexpected, _]]
          res.left.toOption.get.asInstanceOf[Unexpected].thr.isDefined
      }
    }

  }
}
