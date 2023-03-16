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

package uk.gov.hmrc.transitmovementsrouter.controllers

import play.api.mvc.BaseController
import play.api.mvc.ControllerComponents
import play.api.mvc.Headers
import play.api.test.Helpers.stubControllerComponents
import org.scalatest.OptionValues
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.mockito.MockitoSugar
import uk.gov.hmrc.transitmovementsrouter.controllers.errors.PresentationError
import uk.gov.hmrc.transitmovementsrouter.models.ObjectStoreResourceLocation
import uk.gov.hmrc.transitmovementsrouter.models.ObjectStoreURI

class ObjectStoreURIExtractorSpec extends AnyFreeSpec with Matchers with MockitoSugar with ScalaFutures with OptionValues {

  class ObjectStoreURIExtractorImpl extends ObjectStoreURIExtractor with BaseController {
    override protected def controllerComponents: ControllerComponents = stubControllerComponents()
  }

  val objectStoreURIExtractor = new ObjectStoreURIExtractorImpl

  "extractObjectStoreResourceLocation" - {

    "if object store uri supplied is invalid, return BadRequestError" in {

      val result = objectStoreURIExtractor.extractObjectStoreResourceLocation(ObjectStoreURI("invalid"))

      whenReady(result.value) {
        _ mustBe Left(
          PresentationError.badRequestError(s"Object store uri value does not start with common-transit-convention-traders/ (got invalid)")
        )
      }
    }

    "if object store uri supplied is valid, return Right" in {
      val filePath = "common-transit-convention-traders/movements/movementId/abc.xml"

      val result = objectStoreURIExtractor.extractObjectStoreResourceLocation(ObjectStoreURI(filePath))

      whenReady(result.value) {
        _ mustBe Right(ObjectStoreResourceLocation(filePath, "movements/movementId/abc.xml"))
      }
    }

  }

  "extractObjectStoreURIHeader" - {

    "if object store uri header is not supplied, return BadRequestError" in {
      val noObjectStoreURIHeader = Headers("X-Message-Type" -> "IE015")

      val result = objectStoreURIExtractor.extractObjectStoreURIHeader(noObjectStoreURIHeader)

      whenReady(result.value) {
        _ mustBe Left(PresentationError.badRequestError("Missing X-Object-Store-Uri header value"))
      }
    }

    "if object store uri header supplied is invalid, return BadRequestError" in {
      val invalidObjectStoreURIHeader = Headers("X-Object-Store-Uri" -> "invalid")

      val result = objectStoreURIExtractor.extractObjectStoreURIHeader(invalidObjectStoreURIHeader)

      whenReady(result.value) {
        _ mustBe Left(
          PresentationError.badRequestError(s"Object store uri value does not start with common-transit-convention-traders/ (got invalid)")
        )
      }
    }

    "if object store uri header supplied is valid, return Right" in {
      val filePath                  = "common-transit-convention-traders/movements/movementId/abc.xml"
      val validObjectStoreURIHeader = Headers("X-Object-Store-Uri" -> filePath)

      val result = objectStoreURIExtractor.extractObjectStoreURIHeader(validObjectStoreURIHeader)

      whenReady(result.value) {
        _ mustBe Right(ObjectStoreResourceLocation(filePath, "movements/movementId/abc.xml"))
      }
    }

  }

}
