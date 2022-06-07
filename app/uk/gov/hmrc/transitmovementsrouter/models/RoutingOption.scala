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

package uk.gov.hmrc.transitmovementsrouter.models

import uk.gov.hmrc.transitmovementsrouter.config.AppConfig
import uk.gov.hmrc.transitmovementsrouter.config.EISInstanceConfig

object RoutingOption {
  case object Gb extends RoutingOption {
    override val code: String = "GB"
    override def config(appConfig: AppConfig): EISInstanceConfig = appConfig.eisGb
  }
  case object Ni extends RoutingOption {
    override val code: String = "NI"
    override def config(appConfig: AppConfig): EISInstanceConfig = appConfig.eisNi
  }
}
sealed trait RoutingOption {

  def code: String
  def config(appConfig: AppConfig): EISInstanceConfig

}
