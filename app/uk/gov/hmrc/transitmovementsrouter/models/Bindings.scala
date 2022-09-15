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

import play.api.mvc.PathBindable

object Bindings {

  val idPattern = "^[0-9a-f]{16}-[0-9a-f]{16}$".r

  implicit val dualBinding = new PathBindable[(MovementId, MessageId)] {

    override def bind(key: String, value: String): Either[String, (MovementId, MessageId)] =
      if (idPattern.pattern.matcher(value).matches()) {
        val split = value.split('-')
        Right((MovementId(split.head), MessageId(split.last)))
      } else Left(s"$key: Value $value is not a pair of 16 character hexadecimal string's split by a hyphen")

    override def unbind(key: String, value: (MovementId, MessageId)): String = s"${value._1.value}-${value._2.value}"
  }
}
