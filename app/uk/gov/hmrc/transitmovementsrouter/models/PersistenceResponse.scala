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

package uk.gov.hmrc.transitmovementsrouter.models

import play.api.libs.json.Json
import play.api.libs.json.OFormat
import uk.gov.hmrc.transitmovementsrouter.models.formats.CommonFormats

case class PersistenceResponse(messageId: MessageId, eori: EoriNumber, clientId: Option[ClientId], isTransitional: Boolean, sendNotification: Option[Boolean]) {
  def shouldSendNotification: Boolean = sendNotification.getOrElse(true)
}

object PersistenceResponse extends CommonFormats {
  implicit val format: OFormat[PersistenceResponse] = Json.format[PersistenceResponse]
}
