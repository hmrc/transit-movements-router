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

package uk.gov.hmrc.transitmovementsrouter.services

import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.reset
import org.mockito.Mockito.when
import org.scalatest.BeforeAndAfterEach
import org.scalatest.OptionValues
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import play.api.http.Status.INTERNAL_SERVER_ERROR
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.UpstreamErrorResponse
import uk.gov.hmrc.transitmovementsrouter.config.AppConfig
import uk.gov.hmrc.transitmovementsrouter.connectors.SDESConnector
import uk.gov.hmrc.transitmovementsrouter.generators.TestModelGenerators
import uk.gov.hmrc.transitmovementsrouter.models.errors.SDESError
import uk.gov.hmrc.transitmovementsrouter.models.sdes.SdesFilereadyRequest

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

class SDESServiceSpec
    extends AnyFreeSpec
    with Matchers
    with OptionValues
    with ScalaFutures
    with MockitoSugar
    with TestModelGenerators
    with ScalaCheckDrivenPropertyChecks
    with BeforeAndAfterEach {

  implicit val hc: HeaderCarrier = HeaderCarrier()

  val upstreamErrorResponse: Throwable = UpstreamErrorResponse("Internal service error", INTERNAL_SERVER_ERROR)

  val mockConnector: SDESConnector = mock[SDESConnector]

  val mockAppConfig: AppConfig = mock[AppConfig]

  val sut = new SDESServiceImpl(mockAppConfig, mockConnector)

  override def beforeEach(): Unit = {
    reset(mockConnector)
    reset(mockAppConfig)
  }

  "Submitting a large message to SDES" - {

    "on a successful submission to SDES , should return a Right" in forAll(
      arbitraryMovementId.arbitrary,
      arbitraryMessageId.arbitrary,
      arbitraryObjectSummaryWithMd5.arbitrary
    ) {
      (movementId, messageId, objectSummary) =>
        when(
          mockConnector.send(
            any[String].asInstanceOf[SdesFilereadyRequest]
          )(
            any[HeaderCarrier],
            any[ExecutionContext]
          )
        )
          .thenReturn(Future.successful(()))

        val result                            = sut.send(movementId, messageId, objectSummary)
        val expected: Either[SDESError, Unit] = Right(())
        whenReady(result.value) {
          _ mustBe expected
        }
    }

    "on a failed submission to SDES , should return a Left with an UnexpectedError" in forAll(
      arbitraryMovementId.arbitrary,
      arbitraryMessageId.arbitrary,
      arbitraryObjectSummaryWithMd5.arbitrary
    ) {
      (movementId, messageId, objectSummary) =>
        when(
          mockConnector.send(
            any[String].asInstanceOf[SdesFilereadyRequest]
          )(
            any[HeaderCarrier],
            any[ExecutionContext]
          )
        )
          .thenReturn(Future.failed(upstreamErrorResponse))
        val result                            = sut.send(movementId, messageId, objectSummary)
        val expected: Either[SDESError, Unit] = Left(SDESError.UnexpectedError(Some(upstreamErrorResponse)))
        whenReady(result.value) {
          _ mustBe expected
        }
    }

  }
}
