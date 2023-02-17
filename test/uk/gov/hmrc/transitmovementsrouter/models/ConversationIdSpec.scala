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

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

import java.util.UUID

class ConversationIdSpec extends AnyFreeSpec with Matchers {

  "from Movement and Message ID" in {
    val movementId = MovementId("abcdef0123456789")
    val messageId  = MessageId("9876543210fedcba")

    ConversationId(movementId, messageId).value mustBe UUID.fromString("abcdef01-2345-6789-9876-543210fedcba")
  }

  "to Movement and Message ID" in {
    val sut = ConversationId(UUID.fromString("abcdef01-2345-6789-9876-543210fedcba"))
    sut.toMovementAndMessageId mustBe (MovementId("abcdef0123456789"), MessageId("9876543210fedcba"))
  }

}
