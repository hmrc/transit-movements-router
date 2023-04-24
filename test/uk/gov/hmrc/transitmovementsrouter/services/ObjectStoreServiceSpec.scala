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

package uk.gov.hmrc.transitmovementsrouter.services

import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.util.ByteString
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchersSugar.eqTo
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.MockitoSugar.reset
import org.mockito.MockitoSugar.spy
import org.mockito.MockitoSugar.when
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Gen
import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatest.time.Seconds
import org.scalatest.time.Span
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks.forAll
import play.api.http.MimeTypes
import play.api.http.Status.NOT_FOUND
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.UpstreamErrorResponse
import uk.gov.hmrc.objectstore.client.ObjectSummaryWithMd5
import uk.gov.hmrc.objectstore.client.Path
import uk.gov.hmrc.objectstore.client.RetentionPeriod
import uk.gov.hmrc.objectstore.client.RetentionPeriod.SevenYears
import uk.gov.hmrc.objectstore.client.config.ObjectStoreClientConfig
import uk.gov.hmrc.objectstore.client.play.PlayObjectStoreClientEither
import uk.gov.hmrc.transitmovementsrouter.base.TestActorSystem
import uk.gov.hmrc.transitmovementsrouter.config.AppConfig
import uk.gov.hmrc.transitmovementsrouter.fakes.objectstore.ObjectStoreStub
import uk.gov.hmrc.transitmovementsrouter.generators.TestModelGenerators
import uk.gov.hmrc.transitmovementsrouter.models.ConversationId
import uk.gov.hmrc.transitmovementsrouter.models.ObjectStoreResourceLocation
import uk.gov.hmrc.transitmovementsrouter.models.errors.ObjectStoreError
import uk.gov.hmrc.transitmovementsrouter.models.responses.UpscanResponse.DownloadUrl

import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID.randomUUID
import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.Future

class ObjectStoreServiceSpec
    extends AnyFreeSpec
    with Matchers
    with MockitoSugar
    with ScalaFutures
    with TestModelGenerators
    with TestActorSystem
    with BeforeAndAfterEach {

  val baseUrl                         = s"http://baseUrl-${randomUUID().toString}"
  val owner                           = "transit-movements-router"
  val token                           = s"token-${randomUUID().toString}"
  val config: ObjectStoreClientConfig = ObjectStoreClientConfig(baseUrl, owner, token, SevenYears)

  implicit val hc: HeaderCarrier            = HeaderCarrier()
  implicit val ec: ExecutionContextExecutor = materializer.executionContext

  lazy val objectStoreStub: ObjectStoreStub      = spy(new ObjectStoreStub(config))
  val appConfig: AppConfig                       = mock[AppConfig]
  val objectStoreService: ObjectStoreServiceImpl = new ObjectStoreServiceImpl(Clock.systemUTC(), appConfig, objectStoreStub)

  override def beforeEach(): Unit =
    reset(objectStoreStub)

  "On adding incoming message to object store" - {
    "given a successful response from the connector, should return a Right with Object Store Summary" in forAll(
      arbitraryMovementId.arbitrary,
      arbitraryMessageId.arbitrary
    ) {
      (movementId, messageId) =>
        val result = objectStoreService.storeIncoming(
          DownloadUrl("https://bucketName.s3.eu-west-2.amazonaws.com"),
          movementId,
          messageId
        )

        whenReady(result.value, timeout(Span(6, Seconds))) {
          case Left(e)  => fail(e.toString)
          case Right(x) => x
        }
    }

    "given an exception is thrown in the service, should return a Left with the exception in an ObjectStoreError" in forAll(
      arbitraryMovementId.arbitrary,
      arbitraryMessageId.arbitrary
    ) {
      (movementId, messageId) =>
        val result = objectStoreService.storeIncoming(
          DownloadUrl("invalidURL"),
          movementId,
          messageId
        )

        whenReady(result.value) {
          case Right(_) => fail("should have returned a Left")
          case Left(x)  => x
        }
    }
  }

  "On adding outgoing message to object store via copy" - {
    "given a successful response from the connector, should return a Right with Object Store Summary" in forAll(
      arbitraryObjectStoreResourceLocation.arbitrary
    ) {
      objectStoreResourceLocation =>
        when(appConfig.objectStoreUrl).thenReturn("http://localhost:8084/object-store/object")
        val result = objectStoreService.storeOutgoing(
          objectStoreResourceLocation
        )

        whenReady(result.value, timeout(Span(6, Seconds))) {
          case Left(e)  => fail(e.toString)
          case Right(x) => x
        }
    }

    "given an exception is thrown in the service, should return a Left with the exception in an ObjectStoreError" in forAll(
      arbitraryObjectStoreResourceLocation.arbitrary
    ) {
      objectStoreResourceLocation =>
        when(appConfig.objectStoreUrl).thenReturn("invalid")
        val result = objectStoreService.storeOutgoing(
          objectStoreResourceLocation
        )

        whenReady(result.value) {
          case Right(_) => fail("should have returned a Left")
          case Left(x)  => x
        }
    }
  }

  "storeOutgoing (via stream)" - {
    "given a successful response from the connector, should return a Right with Object Store Summary" in forAll(
      arbitrary[ConversationId],
      Gen.alphaNumStr,
      arbitrary[ObjectSummaryWithMd5]
    ) {
      (conversationId, body, summary) =>
        val source = Source.single(ByteString(body))

        val clock: Clock         = Clock.fixed(LocalDateTime.of(2023, 4, 24, 11, 47, 42, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC)
        val appConfig: AppConfig = mock[AppConfig]
        val mockObjectStore      = mock[PlayObjectStoreClientEither]
        val dateFormat           = "20230424-114742"
        when(
          mockObjectStore.putObject[Source[ByteString, _]](
            path = eqTo(Path.File(s"sdes/${conversationId.value.toString}-$dateFormat.xml")),
            content = eqTo(source),
            retentionPeriod = eqTo(RetentionPeriod.OneWeek),
            contentType = eqTo(Some(MimeTypes.XML)),
            contentMd5 = eqTo(None),
            owner = eqTo("transit-movements-router")
          )(any(), any())
        )
          .thenReturn(Future.successful(Right(summary)))
        val objectStoreService: ObjectStoreServiceImpl = new ObjectStoreServiceImpl(clock, appConfig, mockObjectStore)

        when(appConfig.objectStoreUrl).thenReturn("http://localhost:8084/object-store/object")
        val result = objectStoreService.storeOutgoing(
          conversationId,
          source
        )

        whenReady(result.value, timeout(Span(6, Seconds))) {
          case Left(e) => fail(s"Expected Right, instead got Left($e)")
          case Right(x) =>
            x mustBe summary
            verify(mockObjectStore, times(1)).putObject(
              path = eqTo(Path.File(s"sdes/${conversationId.value.toString}-$dateFormat.xml")),
              content = eqTo(source),
              retentionPeriod = eqTo(RetentionPeriod.OneWeek),
              contentType = eqTo(Some(MimeTypes.XML)),
              contentMd5 = eqTo(None),
              owner = eqTo("transit-movements-router")
            )(any(), any())
        }
    }

    "given an exception is thrown in the service, should return a Left with the exception in an ObjectStoreError" in forAll(
      arbitrary[ConversationId],
      Gen.alphaNumStr
    ) {
      (conversationId, body) =>
        val source = Source.single(ByteString(body))

        val exception            = UpstreamErrorResponse("error", NOT_FOUND)
        val clock: Clock         = Clock.fixed(LocalDateTime.of(2023, 4, 24, 11, 47, 42, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC)
        val appConfig: AppConfig = mock[AppConfig]
        val mockObjectStore      = mock[PlayObjectStoreClientEither]
        val dateFormat           = "20230424-114742"
        when(
          mockObjectStore.putObject[Source[ByteString, _]](
            path = eqTo(Path.File(s"sdes/${conversationId.value.toString}-$dateFormat.xml")),
            content = eqTo(source),
            retentionPeriod = eqTo(RetentionPeriod.OneWeek),
            contentType = eqTo(Some(MimeTypes.XML)),
            contentMd5 = eqTo(None),
            owner = eqTo("transit-movements-router")
          )(any(), any())
        )
          .thenReturn(Future.successful(Left(exception)))
        val objectStoreService: ObjectStoreServiceImpl = new ObjectStoreServiceImpl(clock, appConfig, mockObjectStore)

        when(appConfig.objectStoreUrl).thenReturn("http://localhost:8084/object-store/object")

        val result = objectStoreService.storeOutgoing(
          conversationId,
          source
        )

        whenReady(result.value, timeout(Span(6, Seconds))) {
          case Left(ObjectStoreError.UnexpectedError(Some(`exception`))) =>
            verify(mockObjectStore, times(1)).putObject(
              path = eqTo(Path.File(s"sdes/${conversationId.value.toString}-$dateFormat.xml")),
              content = eqTo(source),
              retentionPeriod = eqTo(RetentionPeriod.OneWeek),
              contentType = eqTo(Some(MimeTypes.XML)),
              contentMd5 = eqTo(None),
              owner = eqTo("transit-movements-router")
            )(any(), any())
          case x => fail(s"Expected Left of unexpected error, instead got $x")
        }
    }
  }

  "get file from  object store" - {
    "should return the contents of a file" in {
      val filePath =
        Path.Directory("movements/movementId").file("x-conversation-id.xml").asUri
      val result = objectStoreService.getObjectStoreFile(ObjectStoreResourceLocation("", filePath))

      whenReady(result.value) {
        r =>
          r.isRight mustBe true
          r.toOption.get.runWith(Sink.head).futureValue.utf8String mustBe "content"
      }
    }

    "should return an error when the file is not found on path" in {
      val filePath =
        Path.Directory("abc/movementId").file("x-conversation-id.xml").asUri
      val result = objectStoreService.getObjectStoreFile(ObjectStoreResourceLocation("", filePath))

      whenReady(result.value, timeout(Span(6, Seconds))) {
        case Left(_: ObjectStoreError.FileNotFound) => succeed
        case x =>
          fail(s"Expected Left(ObjectStoreError.FileNotFound), instead got $x")
      }
    }

    "on a failed submission, should return a Left with an UnexpectedError" in {
      val filePath =
        Path.File("x-conversation-id.xml").asUri
      val result = objectStoreService.getObjectStoreFile(ObjectStoreResourceLocation("", filePath))

      whenReady(result.value, timeout(Span(6, Seconds))) {
        case Left(_: ObjectStoreError.UnexpectedError) => succeed
        case x =>
          fail(s"Expected Left(ObjectStoreError.UnexpectedError), instead got $x")
      }
    }

  }

}
