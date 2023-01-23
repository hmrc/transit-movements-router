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

import play.api.mvc.PathBindable

object Bindings {

  val idPattern = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$".r

  implicit val dualBinding = new PathBindable[(MovementId, MessageId)] {

    override def bind(key: String, value: String): Either[String, (MovementId, MessageId)] =
      if (idPattern.pattern.matcher(value).matches()) {
        val split = value.split('-')

        val movementId = split(0) + split(1) + split(2)
        val messageId  = split(3) + split(4)
        Right((MovementId(movementId), MessageId(messageId)))
      } else Left(s"$key: Value $value is not a pair of 32 character hexadecimal string's split by a hyphen")

    override def unbind(key: String, value: (MovementId, MessageId)): String = {
      val movementId  = value._1.value
      val movementId1 = movementId.slice(0, 8)
      val movementId2 = movementId.slice(8, 12)
      val movementId3 = movementId.slice(12, 16)

      val messageId  = value._2.value
      val messageId1 = messageId.slice(0, 4)
      val messageId2 = messageId.slice(4, 16)

      s"$movementId1-$movementId2-$movementId3-$messageId1-$messageId2"
    }
  }
}
