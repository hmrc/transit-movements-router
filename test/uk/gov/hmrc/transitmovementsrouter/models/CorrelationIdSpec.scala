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

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class CorrelationIdSpec extends AnyFreeSpec with Matchers {

  "build CorrelationId from MovementId and MessageId" in {
    val correlationId = CorrelationId(MovementId("63629b56d8d9c033"), MessageId("63629b4d1d74477e"))
    correlationId.value mustBe "63629b56-d8d9-c033-63629b4d-1d74477e"
  }

}
