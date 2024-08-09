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

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppConfig @Inject() (config: Configuration, servicesConfig: CTCServicesConfig) {

  lazy val appName: String = config.get[String]("appName")

  lazy val eisXi: EISInstanceConfig     = config.get[EISInstanceConfig]("microservice.services.eis.xi")
  lazy val eisGb: EISInstanceConfig     = config.get[EISInstanceConfig]("microservice.services.eis.gb")
  lazy val eisGbV2_1: EISInstanceConfig = config.get[EISInstanceConfig]("microservice.services.eis.gb_v2_1")
  lazy val eisXiV2_1: EISInstanceConfig = config.get[EISInstanceConfig]("microservice.services.eis.xi_v2_1")

  lazy val persistenceServiceBaseUrl: Url = Url.parse(servicesConfig.baseUrl("transit-movements"))

  val transitMovementsPushNotificationsUrl: Url = Url.parse(servicesConfig.baseUrl("transit-movements-push-notifications"))

  val pushNotificationsEnabled: Boolean = servicesConfig.config("transit-movements-push-notifications").get[Boolean]("enabled")

  lazy val incomingAuth: IncomingAuthConfig = config.get[IncomingAuthConfig]("incomingRequestAuth")

  lazy val objectStoreUrl: String =
    config.get[String]("microservice.services.object-store.sdes-host")

  lazy val logBodyOnEIS500: Boolean = config.get[Boolean]("microservice.services.eis.log-body-on-500")
  lazy val eisSizeLimit: Long       = config.underlying.getMemorySize("microservice.services.eis.message-size-limit").toBytes
  lazy val logIncoming: Boolean     = config.get[Boolean]("log-incoming-errors")

  // SDES configuration
  lazy val sdesServiceBaseUrl: Url     = Url.parse(servicesConfig.baseUrl("secure-data-exchange-proxy"))
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

  lazy val internalAuthEnabled: Boolean = config.get[Boolean]("microservice.services.internal-auth.enabled")
  lazy val internalAuthToken: String    = config.get[String]("internal-auth.token")

  lazy val serviceMonitoringUrl: String         = servicesConfig.baseUrl("ncts")
  lazy val serviceMonitoringEnabled: Boolean    = config.get[Boolean]("microservice.services.ncts.enabled")
  lazy val serviceMonitoringOutgoingUri: String = config.get[String]("microservice.services.ncts.outgoing-uri")
  lazy val serviceMonitoringIncomingUri: String = config.get[String]("microservice.services.ncts.incoming-uri")

  lazy val auditingUrl: Url = Url.parse(servicesConfig.baseUrl("transit-movements-auditing"))
}
