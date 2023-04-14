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

import akka.stream.Materializer
import com.google.inject.ImplementedBy
import com.google.inject.Inject
import com.google.inject.Singleton
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.transitmovementsrouter.config.AppConfig

import java.time.Clock
import scala.concurrent.ExecutionContext

@ImplementedBy(classOf[EISConnectorProviderImpl])
trait EISConnectorProvider {

  def gb: EISConnector
  def xi: EISConnector

}

@Singleton // singleton as the message connectors need to be singletons for the circuit breakers.
class EISConnectorProviderImpl @Inject() (
  appConfig: AppConfig,
  retries: Retries,
  httpClientV2: HttpClientV2,
  clock: Clock
)(implicit ec: ExecutionContext, mat: Materializer)
    extends EISConnectorProvider {

  lazy val gb: EISConnector = new EISConnectorImpl("GB", appConfig.eisGb, appConfig.headerCarrierConfig, httpClientV2, retries, clock)
  lazy val xi: EISConnector = new EISConnectorImpl("XI", appConfig.eisXi, appConfig.headerCarrierConfig, httpClientV2, retries, clock)

}
