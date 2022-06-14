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

import org.scalatest.concurrent.PatienceConfiguration.Timeout
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import uk.gov.hmrc.transitmovements.base.StreamTestHelpers.createStream
import uk.gov.hmrc.transitmovements.base.TestActorSystem
import uk.gov.hmrc.transitmovementsrouter.models._
import uk.gov.hmrc.transitmovementsrouter.services.RoutingServiceImpl

import scala.concurrent.duration.DurationInt
import scala.xml.NodeSeq

class RoutingServiceSpec extends AnyFreeSpec with ScalaFutures with TestActorSystem with Matchers {

  "Office Of Departure Sink" - new Setup {

    "should return a valid departure office" in {
      val serviceUnderTest = new RoutingServiceImpl()
      val payload          = createStream(cc015cOfficeOfDeparture)
      val (updatedPayload, office) =
        serviceUnderTest.submitDeclaration(MovementType("Departure"), MovementId("movement-001"), MessageId("message-id-001"), payload)

      whenReady(office, Timeout(2 seconds)) {
        _.mustBe(Right(OfficeOfDeparture("Newcastle-airport")))
      }
    }
  }

  trait Setup {

    val cc015cOfficeOfDeparture: NodeSeq =
      <CC015C>
        <messageSender>GB1234</messageSender>
        <preparationDateAndTime>2022-05-25T09:37:04</preparationDateAndTime>
        <CustomsOfficeOfDeparture>
          <referenceNumber>Newcastle-airport</referenceNumber>
        </CustomsOfficeOfDeparture>
      </CC015C>
  }
}
