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

package uk.gov.hmrc.transitmovementsrouter.config

import io.lemonlabs.uri.Url
import io.lemonlabs.uri.UrlPath
import play.api.Configuration
import uk.gov.hmrc.http.HeaderCarrier

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppConfig @Inject() (config: Configuration, servicesConfig: CTCServicesConfig) {

  lazy val appName: String = config.get[String]("appName")

  lazy val eisXi: EISInstanceConfig = config.get[EISInstanceConfig]("microservice.services.eis.xi")
  lazy val eisGb: EISInstanceConfig = config.get[EISInstanceConfig]("microservice.services.eis.gb")

  lazy val headerCarrierConfig: HeaderCarrier.Config = HeaderCarrier.Config.fromConfig(config.underlying)

  lazy val persistenceServiceBaseUrl: Url = Url.parse(servicesConfig.baseUrl("transit-movements"))

  val transitMovementsPushNotificationsUrl = Url.parse(servicesConfig.baseUrl("transit-movements-push-notifications"))

  val pushNotificationsEnabled = servicesConfig.config("transit-movements-push-notifications").get[Boolean]("enabled")

  lazy val messageSizeLimit: Int = config.get[Int]("messageSizeLimit")

  lazy val incomingAuth: IncomingAuthConfig = config.get[IncomingAuthConfig]("incomingRequestAuth")

  lazy val objectStoreUrl: String =
    config.get[String]("microservice.services.object-store.sdes-host")

  // SDES configuration
  lazy val sdesServiceBaseUrl          = Url.parse(servicesConfig.baseUrl("secure-data-exchange-proxy"))
  lazy val sdesInformationType: String = config.get[String]("sdes.information-type")
  lazy val sdesSrn: String             = config.get[String]("sdes.srn")
  lazy val sdesClientId: String        = config.get[String]("sdes.client-id")

  lazy val sdesFileReadyUri: UrlPath =
    UrlPath(
      config
        .get[String]("microservice.services.secure-data-exchange-proxy.file-ready-uri")
        .split("/")
        .filter(_.nonEmpty)
    )
}
