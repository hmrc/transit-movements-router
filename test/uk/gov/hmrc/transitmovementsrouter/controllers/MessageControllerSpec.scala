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

package uk.gov.hmrc.transitmovementsrouter.controllers

import akka.stream.scaladsl.Source
import akka.util.ByteString
import cats.data.EitherT
import org.mockito.ArgumentMatchers.any
import org.mockito.MockitoSugar.reset
import org.mockito.MockitoSugar.when
import org.scalatest.BeforeAndAfterEach
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers.convertToAnyMustWrapper
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar.mock
import play.api.http.DefaultHttpErrorHandler
import play.api.http.HttpErrorConfig
import play.api.http.Status.ACCEPTED
import play.api.http.Status.BAD_REQUEST
import play.api.http.Status.INTERNAL_SERVER_ERROR
import play.api.libs.Files.SingletonTemporaryFileCreator
import play.api.libs.json.Json
import play.api.mvc.PlayBodyParsers
import play.api.mvc.Request
import play.api.test.FakeHeaders
import play.api.test.FakeRequest
import play.api.test.Helpers.contentAsJson
import play.api.test.Helpers.defaultAwaitTimeout
import play.api.test.Helpers.status
import play.api.test.Helpers.stubControllerComponents
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.HttpVerbs.POST
import uk.gov.hmrc.transitmovementsrouter.base.StreamTestHelpers.createStream
import uk.gov.hmrc.transitmovementsrouter.base.TestActorSystem
import uk.gov.hmrc.transitmovementsrouter.models.EoriNumber
import uk.gov.hmrc.transitmovementsrouter.models.MessageId
import uk.gov.hmrc.transitmovementsrouter.models.MovementId
import uk.gov.hmrc.transitmovementsrouter.models.MovementType
import uk.gov.hmrc.transitmovementsrouter.services.RoutingService
import uk.gov.hmrc.transitmovementsrouter.services.error.RoutingError

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.xml.NodeSeq

class MessageControllerSpec extends AnyFreeSpec with Matchers with TestActorSystem with BeforeAndAfterEach {

  val eori         = EoriNumber("eori")
  val movementType = MovementType("departures")
  val movementId   = MovementId("ABC123")
  val messageId    = MessageId("XYZ456")

  val cc015cOfficeOfDepartureGB: NodeSeq =
    <CC015C>
      <preparationDateAndTime>2022-05-25T09:37:04</preparationDateAndTime>
      <CustomsOfficeOfDeparture>
        <referenceNumber>GB1234567</referenceNumber>
      </CustomsOfficeOfDeparture>
    </CC015C>

  val mockRoutingService = mock[RoutingService]

  val errorHandler                    = new DefaultHttpErrorHandler(HttpErrorConfig(showDevErrors = false, None), None, None)
  val controllerComponentWithTempFile = stubControllerComponents(playBodyParsers = PlayBodyParsers(SingletonTemporaryFileCreator, errorHandler)(materializer))

  val controller = new MessagesController(controllerComponentWithTempFile, mockRoutingService)

  def source = createStream(cc015cOfficeOfDepartureGB)

  def fakeRequest[A](
    body: NodeSeq
  ): Request[Source[ByteString, _]] =
    FakeRequest(
      method = POST,
      uri = routes.MessagesController.post(eori, movementType, movementId, messageId).url,
      headers = FakeHeaders(Seq.empty),
      body = createStream(body)
    )

  override def afterEach() {
    reset(mockRoutingService)
    super.afterEach()
  }

  lazy val submitDeclarationEither: EitherT[Future, RoutingError, Unit] =
    EitherT.rightT(())

  "POST /" - {
    "must return ACCEPTED when declaration is submitted successfully" in {

      when(
        mockRoutingService.submitDeclaration(
          any[String].asInstanceOf[MovementType],
          any[String].asInstanceOf[MovementId],
          any[String].asInstanceOf[MessageId],
          any[Source[ByteString, _]]
        )(any[HeaderCarrier])
      ).thenReturn(submitDeclarationEither)

      val result = controller.post(eori, movementType, movementId, messageId)(fakeRequest(cc015cOfficeOfDepartureGB))

      status(result) mustBe ACCEPTED
    }

    "must return BAD_REQUEST when declaration submission fails" - {

      "returns message to indicate element not found" in {

        when(
          mockRoutingService.submitDeclaration(
            any[String].asInstanceOf[MovementType],
            any[String].asInstanceOf[MovementId],
            any[String].asInstanceOf[MessageId],
            any[Source[ByteString, _]]
          )(any[HeaderCarrier])
        ).thenReturn(EitherT[Future, RoutingError, Unit](Future.successful(Left(RoutingError.NoElementFound("messageSender")))))

        val result = controller.post(eori, movementType, movementId, messageId)(fakeRequest(cc015cOfficeOfDepartureGB))

        status(result) mustBe BAD_REQUEST
        contentAsJson(result) mustBe Json.obj(
          "code"    -> "BAD_REQUEST",
          "message" -> "Element messageSender not found"
        )
      }

      "returns message to indicate too many elements" in {

        when(
          mockRoutingService.submitDeclaration(
            any[String].asInstanceOf[MovementType],
            any[String].asInstanceOf[MovementId],
            any[String].asInstanceOf[MessageId],
            any[Source[ByteString, _]]
          )(any[HeaderCarrier])
        ).thenReturn(EitherT[Future, RoutingError, Unit](Future.successful(Left(RoutingError.TooManyElementsFound("eori")))))

        val result = controller.post(eori, movementType, movementId, messageId)(fakeRequest(cc015cOfficeOfDepartureGB))

        status(result) mustBe BAD_REQUEST
        contentAsJson(result) mustBe Json.obj(
          "code"    -> "BAD_REQUEST",
          "message" -> "Found too many elements of type eori"
        )
      }
    }

    "must return INTERNAL_SERVER_ERROR when declaration submission fails due to unexpected error" in {

      when(
        mockRoutingService.submitDeclaration(
          any[String].asInstanceOf[MovementType],
          any[String].asInstanceOf[MovementId],
          any[String].asInstanceOf[MessageId],
          any[Source[ByteString, _]]
        )(any[HeaderCarrier])
      ).thenReturn(
        EitherT[Future, RoutingError, Unit](
          Future.successful(Left(RoutingError.Unexpected("unexpected error", Some(new Exception("An unexpected error occurred")))))
        )
      )

      val result = controller.post(eori, movementType, movementId, messageId)(fakeRequest(cc015cOfficeOfDepartureGB))

      status(result) mustBe INTERNAL_SERVER_ERROR
      contentAsJson(result) mustBe Json.obj(
        "code"    -> "INTERNAL_SERVER_ERROR",
        "message" -> "Internal server error"
      )
    }
  }
}
