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

import cats.data.EitherT
import com.google.inject.ImplementedBy
import com.google.inject.Inject
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.transitmovementsrouter.config.AppConfig
import uk.gov.hmrc.transitmovementsrouter.connectors.ServiceMonitoringConnector
import uk.gov.hmrc.transitmovementsrouter.models.CustomsOffice
import uk.gov.hmrc.transitmovementsrouter.models.MessageId
import uk.gov.hmrc.transitmovementsrouter.models.MessageType
import uk.gov.hmrc.transitmovementsrouter.models.MovementId
import uk.gov.hmrc.transitmovementsrouter.models.errors.ServiceMonitoringError

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.control.NonFatal

@ImplementedBy(classOf[ServiceMonitoringServiceImpl])
trait ServiceMonitoringService {

  def outgoing(movementId: MovementId, messageId: MessageId, messageType: MessageType, office: CustomsOffice)(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): EitherT[Future, ServiceMonitoringError, Unit]

  def incoming(movementId: MovementId, messageId: MessageId, messageType: MessageType)(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): EitherT[Future, ServiceMonitoringError, Unit]

}

class ServiceMonitoringServiceImpl @Inject() (appConfig: AppConfig, statusMonitoringConnector: ServiceMonitoringConnector) extends ServiceMonitoringService {

  override def outgoing(movementId: MovementId, messageId: MessageId, messageType: MessageType, office: CustomsOffice)(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): EitherT[Future, ServiceMonitoringError, Unit] =
    if (appConfig.serviceMonitoringEnabled) {
      EitherT {
        statusMonitoringConnector
          .outgoing(movementId, messageId, messageType, office)
          .map(Right.apply)
          .recover { case NonFatal(ex) =>
            Left(ServiceMonitoringError.Unknown(Some(ex)))
          }
      }
    } else EitherT.rightT(())

  override def incoming(movementId: MovementId, messageId: MessageId, messageType: MessageType)(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): EitherT[Future, ServiceMonitoringError, Unit] =
    if (appConfig.serviceMonitoringEnabled) {
      EitherT {
        statusMonitoringConnector
          .incoming(movementId, messageId, messageType)
          .map(Right.apply)
          .recover { case NonFatal(ex) =>
            Left(ServiceMonitoringError.Unknown(Some(ex)))
          }
      }
    } else EitherT.rightT(())
}
