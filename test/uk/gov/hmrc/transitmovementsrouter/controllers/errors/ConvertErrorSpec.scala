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

package uk.gov.hmrc.transitmovementsrouter.controllers.errors

import cats.syntax.all._
import org.scalatest.OptionValues
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.mockito.MockitoSugar
import uk.gov.hmrc.transitmovementsrouter.models.errors.ErrorCode.BadRequest
import uk.gov.hmrc.transitmovementsrouter.services.error.RoutingError
import uk.gov.hmrc.transitmovementsrouter.services.error.RoutingError.NoElementFound
import uk.gov.hmrc.transitmovementsrouter.services.error.RoutingError.UnrecognisedOffice

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class ConvertErrorSpec extends AnyFreeSpec with Matchers with OptionValues with ScalaFutures with MockitoSugar {

  object Harness extends ConvertError

  import Harness._

  "RoutingError error" - {
    "for a success" in {
      val input = Right[RoutingError, Unit](()).toEitherT[Future]
      whenReady(input.asPresentation.value) {
        _ mustBe Right(())
      }
    }

    "for a failure" in {
      val input = Left[RoutingError, Unit](NoElementFound("test")).toEitherT[Future]
      whenReady(input.asPresentation.value) {
        _ mustBe Left(StandardError("Element test not found", BadRequest))
      }
    }

    "for a validation error return UnrecognisedOffice" in {
      val input = Left[RoutingError, Unit](UnrecognisedOffice("Did not recognise office:AB123456")).toEitherT[Future]
      whenReady(input.asPresentation.value) {
        _ mustBe Left(StandardError("Did not recognise office:AB123456", BadRequest))
      }
    }
  }

}
