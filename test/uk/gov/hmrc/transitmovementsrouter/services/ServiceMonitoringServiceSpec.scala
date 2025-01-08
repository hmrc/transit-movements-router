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
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.when
import org.scalatestplus.mockito.MockitoSugar.mock
import org.scalacheck.Arbitrary.arbitrary
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.transitmovementsrouter.config.AppConfig
import uk.gov.hmrc.transitmovementsrouter.connectors.ServiceMonitoringConnector
import uk.gov.hmrc.transitmovementsrouter.generators.TestModelGenerators
import uk.gov.hmrc.transitmovementsrouter.models.CustomsOffice
import uk.gov.hmrc.transitmovementsrouter.models.MessageId
import uk.gov.hmrc.transitmovementsrouter.models.MessageType
import uk.gov.hmrc.transitmovementsrouter.models.MovementId
import uk.gov.hmrc.transitmovementsrouter.models.errors.ServiceMonitoringError

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class ServiceMonitoringServiceSpec extends AnyFreeSpec with Matchers with TestModelGenerators with ScalaFutures with ScalaCheckDrivenPropertyChecks {

  implicit val hc: HeaderCarrier = HeaderCarrier()

  "outgoing" - {
    "if turned off, will just return Unit" in forAll(arbitrary[MovementId], arbitrary[MessageId], arbitrary[MessageType], arbitrary[CustomsOffice]) {
      (movementId, messageId, messageType, customsOffice) =>
        val mockAppConfig = mock[AppConfig]
        when(mockAppConfig.serviceMonitoringEnabled).thenReturn(false)

        val mockConnector = mock[ServiceMonitoringConnector]

        val sut = new ServiceMonitoringServiceImpl(mockAppConfig, mockConnector)
        whenReady(sut.outgoing(movementId, messageId, messageType, customsOffice).value) {
          case Right(()) =>
            verifyNoInteractions(mockConnector)
          case Left(_) => fail("Expected Right of Unit")
        }
    }

    "if turned on" - {
      val mockAppConfig = mock[AppConfig]
      when(mockAppConfig.serviceMonitoringEnabled).thenReturn(true)

      "and the connector succeeds, return Right of unit" in forAll(
        arbitrary[MovementId],
        arbitrary[MessageId],
        arbitrary[MessageType],
        arbitrary[CustomsOffice]
      ) {
        (movementId, messageId, messageType, customsOffice) =>
          val mockConnector = mock[ServiceMonitoringConnector]
          when(
            mockConnector.outgoing(
              MovementId(eqTo(movementId.value)),
              MessageId(eqTo(messageId.value)),
              eqTo(messageType),
              CustomsOffice(eqTo(customsOffice.value))
            )(any(), any())
          )
            .thenReturn(Future.successful((): Unit))

          val sut = new ServiceMonitoringServiceImpl(mockAppConfig, mockConnector)
          whenReady(sut.outgoing(movementId, messageId, messageType, customsOffice).value) {
            case Right(()) =>
              verify(mockConnector, times(1))
                .outgoing(MovementId(eqTo(movementId.value)), MessageId(eqTo(messageId.value)), eqTo(messageType), CustomsOffice(eqTo(customsOffice.value)))(
                  any(),
                  any()
                )
            case Left(_) => fail("Expecting a Right of Unit")
          }
      }

      "and the connector succeeds, return Left of Unexpected" in forAll(
        arbitrary[MovementId],
        arbitrary[MessageId],
        arbitrary[MessageType],
        arbitrary[CustomsOffice]
      ) {
        (movementId, messageId, messageType, customsOffice) =>
          val mockConnector = mock[ServiceMonitoringConnector]
          val expected      = new IllegalStateException("error")
          when(
            mockConnector.outgoing(
              MovementId(eqTo(movementId.value)),
              MessageId(eqTo(messageId.value)),
              eqTo(messageType),
              CustomsOffice(eqTo(customsOffice.value))
            )(any(), any())
          )
            .thenReturn(Future.failed(expected))

          val sut = new ServiceMonitoringServiceImpl(mockAppConfig, mockConnector)
          whenReady(sut.outgoing(movementId, messageId, messageType, customsOffice).value) {
            case Left(ServiceMonitoringError.Unknown(Some(`expected`))) =>
              verify(mockConnector, times(1))
                .outgoing(MovementId(eqTo(movementId.value)), MessageId(eqTo(messageId.value)), eqTo(messageType), CustomsOffice(eqTo(customsOffice.value)))(
                  any(),
                  any()
                )
            case _ => fail("Expecting a Left of an Unexpected")
          }
      }

    }
  }

  "incoming" - {
    "if turned off, will just return Unit" in forAll(arbitrary[MovementId], arbitrary[MessageId], arbitrary[MessageType]) {
      (movementId, messageId, messageType) =>
        val mockAppConfig = mock[AppConfig]
        when(mockAppConfig.serviceMonitoringEnabled).thenReturn(false)

        val mockConnector = mock[ServiceMonitoringConnector]

        val sut = new ServiceMonitoringServiceImpl(mockAppConfig, mockConnector)
        whenReady(sut.incoming(movementId, messageId, messageType).value) {
          case Right(()) =>
            verifyNoInteractions(mockConnector)
          case Left(_) => fail("Expected Right of Unit")
        }
    }

    "if turned on" - {
      val mockAppConfig = mock[AppConfig]
      when(mockAppConfig.serviceMonitoringEnabled).thenReturn(true)

      "and the connector succeeds, return Right of unit" in forAll(arbitrary[MovementId], arbitrary[MessageId], arbitrary[MessageType]) {
        (movementId, messageId, messageType) =>
          val mockConnector = mock[ServiceMonitoringConnector]
          when(mockConnector.incoming(MovementId(eqTo(movementId.value)), MessageId(eqTo(messageId.value)), eqTo(messageType))(any(), any()))
            .thenReturn(Future.successful((): Unit))

          val sut = new ServiceMonitoringServiceImpl(mockAppConfig, mockConnector)
          whenReady(sut.incoming(movementId, messageId, messageType).value) {
            case Right(()) =>
              verify(mockConnector, times(1))
                .incoming(MovementId(eqTo(movementId.value)), MessageId(eqTo(messageId.value)), eqTo(messageType))(any(), any())
            case Left(_) => fail("Expecting a Right of Unit")
          }
      }

      "and the connector succeeds, return Left of Unexpected" in forAll(arbitrary[MovementId], arbitrary[MessageId], arbitrary[MessageType]) {
        (movementId, messageId, messageType) =>
          val mockConnector = mock[ServiceMonitoringConnector]
          val expected      = new IllegalStateException("error")
          when(mockConnector.incoming(MovementId(eqTo(movementId.value)), MessageId(eqTo(messageId.value)), eqTo(messageType))(any(), any()))
            .thenReturn(Future.failed(expected))

          val sut = new ServiceMonitoringServiceImpl(mockAppConfig, mockConnector)
          whenReady(sut.incoming(movementId, messageId, messageType).value) {
            case Left(ServiceMonitoringError.Unknown(Some(`expected`))) =>
              verify(mockConnector, times(1))
                .incoming(MovementId(eqTo(movementId.value)), MessageId(eqTo(messageId.value)), eqTo(messageType))(any(), any())
            case _ => fail("Expecting a Left of an Unexpected")
          }
      }

    }
  }

}
