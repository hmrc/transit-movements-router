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

import play.api.ConfigLoader

import scala.jdk.CollectionConverters._

object IncomingAuthConfig {

  implicit lazy val configLoader: ConfigLoader[IncomingAuthConfig] = ConfigLoader {
    rootConfig => rootPath =>
      IncomingAuthConfig(
        rootConfig.getConfig(rootPath).getBoolean("enabled"),
        rootConfig.getConfig(rootPath).getStringList("acceptedTokens").asScala.toSeq
      )
  }
}

case class IncomingAuthConfig(enabled: Boolean, acceptedTokens: Seq[String])
