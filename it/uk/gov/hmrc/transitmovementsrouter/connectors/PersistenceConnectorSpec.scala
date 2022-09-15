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
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
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
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.test.HttpClientV2Support
import uk.gov.hmrc.transitmovementsrouter.config.AppConfig
import uk.gov.hmrc.transitmovementsrouter.models.MessageId
import uk.gov.hmrc.transitmovementsrouter.models.MessageType.DeclarationAmendment
import uk.gov.hmrc.transitmovementsrouter.models.MovementId
import uk.gov.hmrc.transitmovementsrouter.models.errors.PersistenceError.MovementNotFound
import uk.gov.hmrc.transitmovementsrouter.models.errors.PersistenceError.Unexpected
import uk.gov.hmrc.transitmovementsrouter.services.StreamingMessageTrimmer
import uk.gov.hmrc.transitmovementsrouter.services.StreamingMessageTrimmerImpl

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

  val uriPersistence = s"/transit-movements/traders/movements/${movementId.value}/messages/${messageId.value}"

  val appConfig = mock[AppConfig]

  when(appConfig.persistenceServiceBaseUrl).thenAnswer {
    _ =>
      Url.parse(server.baseUrl())
  }

  implicit val hc = HeaderCarrier()

  lazy val connector = new PersistenceConnectorImpl(httpClientV2, appConfig)

  val errorCodes = Gen.oneOf(
    Seq(
      BAD_REQUEST,
      INTERNAL_SERVER_ERROR,
      NOT_FOUND
    )
  )

  def source: Source[ByteString, _] = Source.single(ByteString.fromString("<TraderChannelResponse><CC013C><CC013C></TraderChannelResponse>"))

  def stub(codeToReturn: Int) = server.stubFor(
    post(
      urlEqualTo(uriPersistence)
    )
      .willReturn(aResponse().withStatus(codeToReturn))
  )

  "post" should {
    "return a unit when post is successful" in {

      stub(OK)

      whenReady(connector.post(movementId, messageId, DeclarationAmendment, source).value) {
        x =>
          x.isRight mustBe true
          x mustBe a[Right[_, Unit]]
      }
    }

    "return a PersistenceError when unsuccessful" in forAll(errorCodes) {
      statusCode =>
        server.resetAll()

        stub(statusCode)

        whenReady(connector.post(movementId, messageId, DeclarationAmendment, source).value) {
          x =>
            x.isLeft mustBe true

            statusCode match {
              case BAD_REQUEST           => x mustBe a[Left[Unexpected, _]]
              case NOT_FOUND             => x mustBe a[Left[MovementNotFound, _]]
              case INTERNAL_SERVER_ERROR => x mustBe a[Left[Unexpected, _]]
            }

        }
    }

    "return Unexpected(throwable) when NonFatal exception is thrown" in {
      server.resetAll()

      stub(OK)

      val failingSource = new StreamingMessageTrimmerImpl().trim(Source.single(ByteString.fromString("<abc>asdadsadads")))

      whenReady(connector.post(movementId, messageId, DeclarationAmendment, failingSource).value) {
        res =>
          res mustBe a[Left[Unexpected, _]]
          res.left.get.asInstanceOf[Unexpected].thr.isDefined
      }
    }

  }
}
