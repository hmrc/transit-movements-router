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

package uk.gov.hmrc.transitmovementsrouter.models.sdes

import play.api.libs.json._
import uk.gov.hmrc.transitmovementsrouter.utils.RouterHeaderNames

import java.time.Instant

case class SdesNotification(
  notification: SdesNotificationType,
  filename: String,
  correlationID: String,
  checksumAlgorithm: String,
  checksum: String,
  availableUntil: Instant,
  failureReason: Option[String],
  dateTime: Instant,
  properties: Seq[SdesProperties]
) {

  val conversationId = properties
    .find(
      p => p.name == RouterHeaderNames.CONVERSATION_ID.toLowerCase()
    )
}

object SdesNotification {
  implicit val sdesNotification = Json.format[SdesNotification]
}
