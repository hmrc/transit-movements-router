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

import java.util.UUID

object ConversationId {

  def apply(movementId: MovementId, messageId: MessageId): ConversationId = {
    val movementPart = s"${movementId.value.substring(0, 8)}-${movementId.value.substring(8, 12)}-${movementId.value.substring(12)}"
    val messagePart  = s"${messageId.value.substring(0, 4)}-${messageId.value.substring(4)}"
    ConversationId(UUID.fromString(s"$movementPart-$messagePart"))
  }
}

case class ConversationId(value: UUID) extends AnyVal {

  def toMovementAndMessageId: (MovementId, MessageId) = {
    val stripped = value.toString.replace("-", "")
    (MovementId(stripped.substring(0, 16)), MessageId(stripped.substring(16)))
  }
}