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

package uk.gov.hmrc.transitmovements.router.services

import org.scalatest.concurrent.ScalaFutures
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import uk.gov.hmrc.transitmovements.base.StreamTestHelpers.createStream
import uk.gov.hmrc.transitmovements.base.TestActorSystem
import uk.gov.hmrc.transitmovementsrouter.models._
import uk.gov.hmrc.transitmovementsrouter.services.RoutingServiceImpl

import scala.concurrent.Await
import concurrent.duration.Duration.Inf
import scala.xml.NodeSeq

class RoutingServiceSpec extends AnyFreeSpec with ScalaFutures with TestActorSystem with Matchers {

  "Office Of Destination Sink" - new Setup {
    "should return a valid departure office" in {
      val serviceUnderTest = new RoutingServiceImpl()
      val payload          = createStream(cc015cOfficeOfDeparture)
      val (updatedPayload, office) =
        serviceUnderTest.submitDeclaration(MovementType("Departure"), MovementId("movement-001"), MessageId("message-id-001"), payload)

      val officeResult = Await.ready(office, Inf)

      officeResult mustBe Some(OfficeOfDeparture("Newcastle-0001"))
    }
  }

  trait Setup {

    val cc015cOfficeOfDeparture: NodeSeq =
      <CustomsOfficeOfDeparture>
        <referenceNumber>Newcastle-0001</referenceNumber>
      </CustomsOfficeOfDeparture>
  }
}
