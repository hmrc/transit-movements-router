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
import org.mockito.ArgumentMatchers.{eq => eqTo}
import org.mockito.Mockito.when
import org.scalacheck.Arbitrary.arbitrary
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
import uk.gov.hmrc.objectstore.client.ObjectSummaryWithMd5
import uk.gov.hmrc.transitmovementsrouter.config.AppConfig
import uk.gov.hmrc.transitmovementsrouter.connectors.SDESConnector
import uk.gov.hmrc.transitmovementsrouter.generators.TestModelGenerators
import uk.gov.hmrc.transitmovementsrouter.models.MessageId
import uk.gov.hmrc.transitmovementsrouter.models.MovementId
import uk.gov.hmrc.transitmovementsrouter.models.errors.SDESError
import uk.gov.hmrc.transitmovementsrouter.models.sdes.FileMd5Checksum
import uk.gov.hmrc.transitmovementsrouter.models.sdes.FileName
import uk.gov.hmrc.transitmovementsrouter.models.sdes.FileSize
import uk.gov.hmrc.transitmovementsrouter.models.sdes.FileURL

import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContext.Implicits.global
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

  "Submitting a large message to SDES" - {

    "on a successful submission to SDES, should return a Right" in forAll(
      arbitrary[MovementId],
      arbitrary[MessageId],
      arbitrary[ObjectSummaryWithMd5]
    ) {
      (movementId, messageId, objectSummary) =>
        val mockAppConfig = mock[AppConfig]
        when(mockAppConfig.objectStoreUrl).thenReturn("http://test")

        val mockConnector: SDESConnector = mock[SDESConnector]
        val sut                          = new SDESServiceImpl(mockAppConfig, mockConnector)
        when(
          mockConnector.send(
            MovementId(eqTo(movementId.value)),
            MessageId(eqTo(messageId.value)),
            FileName(eqTo(objectSummary.location.fileName)),
            FileURL(eqTo(s"http://test/${objectSummary.location.asUri}")),
            FileMd5Checksum(eqTo(FileMd5Checksum.fromBase64(objectSummary.contentMd5).value)),
            FileSize(eqTo(objectSummary.contentLength))
          )(
            any[HeaderCarrier],
            any[ExecutionContext]
          )
        )
          .thenReturn(Future.successful(Right((): Unit)))

        val result = sut.send(movementId, messageId, objectSummary)
        whenReady(result.value) {
          _ mustBe Right(())
        }
    }

    "on a failed submission to SDES, should return a Left with an UnexpectedError" in forAll(
      arbitrary[MovementId],
      arbitrary[MessageId],
      arbitrary[ObjectSummaryWithMd5]
    ) {
      (movementId, messageId, objectSummary) =>
        val mockAppConfig = mock[AppConfig]
        when(mockAppConfig.objectStoreUrl).thenReturn("http://test")

        val mockConnector: SDESConnector     = mock[SDESConnector]
        val sut                              = new SDESServiceImpl(mockAppConfig, mockConnector)
        val upstreamErrorResponse: Throwable = UpstreamErrorResponse("Internal service error", INTERNAL_SERVER_ERROR)
        when(
          mockConnector.send(
            MovementId(eqTo(movementId.value)),
            MessageId(eqTo(messageId.value)),
            FileName(eqTo(objectSummary.location.fileName)),
            FileURL(eqTo(s"http://test/${objectSummary.location.asUri}")),
            FileMd5Checksum(eqTo(FileMd5Checksum.fromBase64(objectSummary.contentMd5).value)),
            FileSize(eqTo(objectSummary.contentLength))
          )(
            any[HeaderCarrier],
            any[ExecutionContext]
          )
        )
          .thenReturn(Future.successful(Left(SDESError.UnexpectedError(Some(upstreamErrorResponse)))))
        val result = sut.send(movementId, messageId, objectSummary)
        whenReady(result.value) {
          _ mustBe Left(SDESError.UnexpectedError(Some(upstreamErrorResponse)))
        }
    }

  }
}
