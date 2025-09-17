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

import org.apache.pekko.stream.Materializer
import com.google.inject.ImplementedBy
import com.google.inject.Inject
import com.google.inject.Singleton
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.transitmovementsrouter.config.AppConfig
import uk.gov.hmrc.transitmovementsrouter.config.EISInstanceConfig

import java.time.Clock
import scala.concurrent.ExecutionContext

@ImplementedBy(classOf[EISConnectorProviderImpl])
trait EISConnectorProvider {
  def gbV2_1: EISConnector

  def xiV2_1: EISConnector

  def gbV3_0: EISConnector

  def xiV3_0: EISConnector
}

@Singleton // singleton as the message connectors need to be singletons for the circuit breakers.
class EISConnectorProviderImpl @Inject() (
  appConfig: AppConfig,
  retries: Retries,
  httpClientV2: HttpClientV2,
  clock: Clock
)(implicit ec: ExecutionContext, mat: Materializer)
    extends EISConnectorProvider {

  lazy val gbV2_1: EISConnector = createConnector("GB", appConfig.eisGbV2_1)
  lazy val xiV2_1: EISConnector = createConnector("XI", appConfig.eisXiV2_1)
  lazy val gbV3_0: EISConnector = createConnector("GB", appConfig.eisGbV3_0)
  lazy val xiV3_0: EISConnector = createConnector("XI", appConfig.eisXiV3_0)

  private def createConnector(code: String, config: EISInstanceConfig) =
    new EISConnectorImpl(code, config, httpClientV2, retries, clock, appConfig.logBodyOnEIS500)

}
