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

package uk.gov.hmrc.transitmovementsrouter.models.requests

import play.api.libs.json.JsString
import play.api.libs.json.Json
import play.api.libs.json.OWrites
import play.api.libs.json.Writes
import uk.gov.hmrc.transitmovementsrouter.models.ConversationId
import uk.gov.hmrc.transitmovementsrouter.models.CustomsOffice
import uk.gov.hmrc.transitmovementsrouter.models.MessageType

import java.time.LocalDateTime
import scala.annotation.unused

object ServiceMonitoringRequests {

  @unused // this is used in the macro below, but otherwise not used so the IDE flags it as such
  implicit private val messageTypeWrites: Writes[MessageType] = Writes {
    messageType => JsString(messageType.code)
  }

  @unused // this is used in the macro below, but otherwise not used so the IDE flags it as such
  implicit private val officeWrites: Writes[CustomsOffice] = Writes {
    office =>
      if (office.value.length > 2) JsString(office.value.substring(0, 2))
      else JsString(office.value)
  }

  implicit val outgoingWrites: OWrites[OutgoingServiceMonitoringRequest] = Json.writes[OutgoingServiceMonitoringRequest]
  implicit val incomingWrites: OWrites[IncomingServiceMonitoringRequest] = Json.writes[IncomingServiceMonitoringRequest]

}

case class IncomingServiceMonitoringRequest(id: ConversationId, timestamp: LocalDateTime, messageCode: MessageType)
case class OutgoingServiceMonitoringRequest(id: ConversationId, timestamp: LocalDateTime, messageCode: MessageType, office: CustomsOffice)
