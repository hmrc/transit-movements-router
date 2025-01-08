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

import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.{eq => eqTo}
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.when
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Gen
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
import uk.gov.hmrc.transitmovementsrouter.generators.TestModelGenerators
import uk.gov.hmrc.transitmovementsrouter.models.ConversationId
import uk.gov.hmrc.transitmovementsrouter.models.errors.ObjectStoreError

import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID.randomUUID
import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.Future

class ObjectStoreServiceSpec extends AnyFreeSpec with Matchers with MockitoSugar with ScalaFutures with TestModelGenerators with TestActorSystem {

  val baseUrl                         = s"http://baseUrl-${randomUUID().toString}"
  val owner                           = "transit-movements-router"
  val token                           = s"token-${randomUUID().toString}"
  val config: ObjectStoreClientConfig = ObjectStoreClientConfig(baseUrl, owner, token, SevenYears)

  implicit val hc: HeaderCarrier            = HeaderCarrier()
  implicit val ec: ExecutionContextExecutor = materializer.executionContext

  val mockObjectStore: PlayObjectStoreClientEither = mock[PlayObjectStoreClientEither]

  val appConfig: AppConfig                       = mock[AppConfig]
  val objectStoreService: ObjectStoreServiceImpl = new ObjectStoreServiceImpl(Clock.systemUTC(), mockObjectStore)

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
          mockObjectStore.putObject[Source[ByteString, ?]](
            path = eqTo(Path.File(s"sdes/${conversationId.value.toString}-$dateFormat.xml")),
            content = eqTo(source),
            retentionPeriod = eqTo(RetentionPeriod.OneWeek),
            contentType = eqTo(Some(MimeTypes.XML)),
            contentMd5 = eqTo(None),
            owner = eqTo("transit-movements-router")
          )(any(), any())
        )
          .thenReturn(Future.successful(Right(summary)))
        val objectStoreService: ObjectStoreServiceImpl = new ObjectStoreServiceImpl(clock, mockObjectStore)

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
          mockObjectStore.putObject[Source[ByteString, ?]](
            path = eqTo(Path.File(s"sdes/${conversationId.value.toString}-$dateFormat.xml")),
            content = eqTo(source),
            retentionPeriod = eqTo(RetentionPeriod.OneWeek),
            contentType = eqTo(Some(MimeTypes.XML)),
            contentMd5 = eqTo(None),
            owner = eqTo("transit-movements-router")
          )(any(), any())
        )
          .thenReturn(Future.successful(Left(exception)))
        val objectStoreService: ObjectStoreServiceImpl = new ObjectStoreServiceImpl(clock, mockObjectStore)

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

}
