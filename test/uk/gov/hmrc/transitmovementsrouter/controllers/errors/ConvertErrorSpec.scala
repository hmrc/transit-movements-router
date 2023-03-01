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

package uk.gov.hmrc.transitmovementsrouter.controllers.errors

import cats.syntax.all._
import org.scalacheck.Gen
import org.scalatest.OptionValues
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import uk.gov.hmrc.http.UpstreamErrorResponse
import uk.gov.hmrc.transitmovementsrouter.models.CustomsOffice
import uk.gov.hmrc.transitmovementsrouter.models.MovementId
import uk.gov.hmrc.transitmovementsrouter.models.errors.MessageTypeExtractionError
import uk.gov.hmrc.transitmovementsrouter.models.errors.PersistenceError
import uk.gov.hmrc.transitmovementsrouter.models.errors.PushNotificationError
import uk.gov.hmrc.transitmovementsrouter.models.errors.ErrorCode.BadRequest
import uk.gov.hmrc.transitmovementsrouter.models.errors.ErrorCode.InternalServerError
import uk.gov.hmrc.transitmovementsrouter.models.errors.ErrorCode.InvalidOffice
import uk.gov.hmrc.transitmovementsrouter.models.errors.ErrorCode.NotFound
import uk.gov.hmrc.transitmovementsrouter.models.errors.MessageTypeExtractionError.InvalidMessageType
import uk.gov.hmrc.transitmovementsrouter.models.errors.MessageTypeExtractionError.UnableToExtractFromBody
import uk.gov.hmrc.transitmovementsrouter.services.error.RoutingError
import uk.gov.hmrc.transitmovementsrouter.services.error.RoutingError.BadDateTime
import uk.gov.hmrc.transitmovementsrouter.services.error.RoutingError.NoElementFound
import uk.gov.hmrc.transitmovementsrouter.services.error.RoutingError.TooManyElementsFound
import uk.gov.hmrc.transitmovementsrouter.services.error.RoutingError.Unexpected
import uk.gov.hmrc.transitmovementsrouter.services.error.RoutingError.UnrecognisedOffice
import uk.gov.hmrc.transitmovementsrouter.services.error.RoutingError.Upstream

import java.time.format.DateTimeParseException
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class ConvertErrorSpec extends AnyFreeSpec with Matchers with OptionValues with ScalaFutures with MockitoSugar with ScalaCheckDrivenPropertyChecks {

  object Harness extends ConvertError

  import Harness._

  "RoutingError error" - {
    "for a success" in {
      val input = Right[RoutingError, Unit](()).toEitherT[Future]
      whenReady(input.asPresentation.value) {
        _ mustBe Right(())
      }
    }

    "for a failure - handle NoElementFound error" in {
      val input = Left[RoutingError, Unit](NoElementFound("test")).toEitherT[Future]
      whenReady(input.asPresentation.value) {
        _ mustBe Left(StandardError("Element test not found", BadRequest))
      }
    }

    "for a validation error return UnrecognisedOffice" in forAll(Gen.alphaNumStr, Gen.alphaStr) {
      (office, field) =>
        val input = Left[RoutingError, Unit](UnrecognisedOffice(s"Did not recognise office:$office", CustomsOffice(office), field)).toEitherT[Future]
        whenReady(input.asPresentation.value) {
          _ mustBe Left(InvalidOfficeError(s"Did not recognise office:$office", office, field, InvalidOffice))
        }
    }

    "an Unexpected Error with exception returns an internal service error with an exception" in {
      val exception = new IllegalStateException()
      val input     = Left[RoutingError, Unit](Unexpected("Unexpected error", Some(exception))).toEitherT[Future]
      whenReady(input.asPresentation.value) {
        _ mustBe Left(InternalServiceError("Internal server error", InternalServerError, Some(exception)))
      }
    }

    "an Unexpected Error with no exception returns an internal service error with no exception" in {
      val input = Left[RoutingError, Unit](Unexpected("Unexpected error", None)).toEitherT[Future]
      whenReady(input.asPresentation.value) {
        _ mustBe Left(InternalServiceError("Internal server error", InternalServerError, None))
      }
    }

    "an Upstream Error Response returns an internal service error" in {
      val response = UpstreamErrorResponse("error", 500)
      val input    = Left[RoutingError, Unit](Upstream(response)).toEitherT[Future]
      whenReady(input.asPresentation.value) {
        _ mustBe Left(InternalServiceError("Internal server error", InternalServerError, Some(response.getCause)))
      }
    }

    "for a failure - handle TooManyElementsFound error" in {
      val input = Left[RoutingError, Unit](TooManyElementsFound("test")).toEitherT[Future]
      whenReady(input.asPresentation.value) {
        _ mustBe Left(StandardError("Found too many elements of type test", BadRequest))
      }
    }

    "for a failure - handle BadDateTime error" in {
      val input = Left[RoutingError, Unit](BadDateTime("test", new DateTimeParseException("parse error", new StringBuilder("error"), 0))).toEitherT[Future]
      whenReady(input.asPresentation.value) {
        _ mustBe Left(StandardError("Could not parse datetime for test: parse error", BadRequest))
      }
    }

  }

  "PersistenceError error" - {
    "an Unexpected Error with exception returns an internal service error with an exception" in {
      val exception = new IllegalStateException()
      val input     = Left[PersistenceError, Unit](PersistenceError.Unexpected(Some(exception))).toEitherT[Future]
      whenReady(input.asPresentation.value) {
        _ mustBe Left(InternalServiceError("Internal server error", InternalServerError, Some(exception)))
      }
    }

    "an Unexpected Error with no exception returns an internal service error with no exception" in {
      val input = Left[PersistenceError, Unit](PersistenceError.Unexpected(None)).toEitherT[Future]
      whenReady(input.asPresentation.value) {
        _ mustBe Left(InternalServiceError("Internal server error", InternalServerError, None))
      }
    }

    "for a failure - handle MovementNotFound error" in {
      val input = Left[PersistenceError, Unit](PersistenceError.MovementNotFound(MovementId("345"))).toEitherT[Future]
      whenReady(input.asPresentation.value) {
        _ mustBe Left(StandardError("Movement 345 not found", NotFound))
      }
    }
  }

  "PushNotificationError error" - {
    "an Unexpected Error with exception returns an internal service error with an exception" in {
      val exception = new IllegalStateException()
      val input     = Left[PushNotificationError, Unit](PushNotificationError.Unexpected(Some(exception))).toEitherT[Future]
      whenReady(input.asPresentation.value) {
        _ mustBe Left(InternalServiceError("Internal server error", InternalServerError, Some(exception)))
      }
    }

    "an Unexpected Error with no exception returns an internal service error with no exception" in {
      val input = Left[PushNotificationError, Unit](PushNotificationError.Unexpected(None)).toEitherT[Future]
      whenReady(input.asPresentation.value) {
        _ mustBe Left(InternalServiceError("Internal server error", InternalServerError, None))
      }
    }

    "for a failure - handle MovementNotFound error" in {
      val input = Left[PushNotificationError, Unit](PushNotificationError.MovementNotFound(MovementId("345"))).toEitherT[Future]
      whenReady(input.asPresentation.value) {
        _ mustBe Left(StandardError("Movement 345 not found", NotFound))
      }
    }
  }

  "HeaderExtractError error" - {
    "for a failure - handle UnableToExtractFromBody error" in {
      val input = Left[MessageTypeExtractionError, Unit](UnableToExtractFromBody).toEitherT[Future]
      whenReady(input.asPresentation.value) {
        _ mustBe Left(StandardError("Message appears to be malformed -- message type was not detected", BadRequest))
      }
    }
    "for a failure - handle InvalidMessageType error" in {
      val input = Left[MessageTypeExtractionError, Unit](InvalidMessageType("code")).toEitherT[Future]
      whenReady(input.asPresentation.value) {
        _ mustBe Left(StandardError("Invalid message type: code", BadRequest))
      }
    }
  }

  "ObjectStore error" - {
    import uk.gov.hmrc.transitmovementsrouter.services.error.ObjectStoreError
    import uk.gov.hmrc.transitmovementsrouter.services.error.ObjectStoreError._

    "for a success" in {
      val input = Right[ObjectStoreError, Unit](()).toEitherT[Future]
      whenReady(input.asPresentation.value) {
        _ mustBe Right(())
      }
    }

    "for a failure" in {
      val exception = new Exception("unexpected failure")
      val input     = Left[ObjectStoreError, Unit](UnexpectedError(Some(exception))).toEitherT[Future]
      whenReady(input.asPresentation.value) {
        _ mustBe Left(InternalServiceError("Internal server error", InternalServerError, Some(exception)))
      }
    }

    "FileNotFound should result BadRequest status" in {
      val input = Left[ObjectStoreError, Unit](FileNotFound("test")).toEitherT[Future]
      whenReady(input.asPresentation.value) {
        _.left.toOption.get.code mustBe BadRequest
      }
    }
  }

}
