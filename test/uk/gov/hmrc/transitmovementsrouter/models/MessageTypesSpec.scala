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

import org.scalacheck.Gen
import org.scalatest.OptionValues
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import uk.gov.hmrc.transitmovementsrouter.models.MessageType.AmendmentAcceptance
import uk.gov.hmrc.transitmovementsrouter.models.MessageType.ArrivalNotification
import uk.gov.hmrc.transitmovementsrouter.models.MessageType.DeclarationAmendment
import uk.gov.hmrc.transitmovementsrouter.models.MessageType.DeclarationData
import uk.gov.hmrc.transitmovementsrouter.models.MessageType.UnloadingPermission
import uk.gov.hmrc.transitmovementsrouter.models.MessageType.UnloadingRemarks

class MessageTypesSpec extends AnyFreeSpec with Matchers with MockitoSugar with OptionValues with ScalaCheckDrivenPropertyChecks {
  "MessageType must contain" - {
    "UnloadingRemarks" in {
      MessageType.values must contain(UnloadingRemarks)
      UnloadingRemarks.code mustEqual "IE044"
      UnloadingRemarks.rootNode mustEqual "CC044C"
      MessageType.arrivalRequestValues must contain(UnloadingRemarks)
    }

    "ArrivalNotification" in {
      MessageType.values must contain(ArrivalNotification)
      ArrivalNotification.code mustEqual "IE007"
      ArrivalNotification.rootNode mustEqual "CC007C"
      MessageType.arrivalRequestValues must contain(ArrivalNotification)
    }

    "UnloadingPermission" in {
      MessageType.values must contain(UnloadingPermission)
      UnloadingPermission.code mustEqual "IE043"
      UnloadingPermission.rootNode mustEqual "CC043C"
      MessageType.arrivalResponseValues must contain(UnloadingPermission)
    }

    "AmendmentAcceptance" in {
      MessageType.values must contain(AmendmentAcceptance)
      AmendmentAcceptance.code mustEqual "IE004"
      AmendmentAcceptance.rootNode mustEqual "CC004C"
      MessageType.departureResponseValues must contain(AmendmentAcceptance)
    }

    "DeclarationAmendment" in {
      MessageType.values must contain(DeclarationAmendment)
      DeclarationAmendment.code mustEqual "IE013"
      DeclarationAmendment.rootNode mustEqual "CC013C"
      MessageType.departureRequestValues must contain(DeclarationAmendment)
    }

    "DeclarationData" in {
      MessageType.values must contain(DeclarationData)
      DeclarationData.code mustEqual "IE015"
      DeclarationData.rootNode mustEqual "CC015C"
      MessageType.departureRequestValues must contain(DeclarationData)
    }

  }

  "find" - {
    "must return None when junk is provided" in forAll(Gen.stringOfN(6, Gen.alphaNumChar)) { code =>
      MessageType.withCode(code) must not be defined
    }

    "must return the correct message type when a correct code is provided" in forAll(Gen.oneOf(MessageType.values)) { messageType =>
      MessageType.withCode(messageType.code) mustBe Some(messageType)
    }
  }
}
