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
import org.scalacheck.Gen
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import play.api.mvc.Headers
import uk.gov.hmrc.transitmovementsrouter.base.TestActorSystem
import uk.gov.hmrc.transitmovementsrouter.base.TestSourceProvider
import uk.gov.hmrc.transitmovementsrouter.models.MessageType
import uk.gov.hmrc.transitmovementsrouter.models.errors.MessageTypeExtractionError.InvalidMessageType
import uk.gov.hmrc.transitmovementsrouter.models.errors.MessageTypeExtractionError.UnableToExtractFromBody
import uk.gov.hmrc.transitmovementsrouter.models.errors.MessageTypeExtractionError.UnableToExtractFromHeader
import uk.gov.hmrc.transitmovementsrouter.models.errors.MessageTypeExtractionError.Unexpected

import scala.concurrent.ExecutionContext.Implicits.global

class MessageTypeExtractorSpec extends AnyFreeSpec with ScalaFutures with Matchers with ScalaCheckPropertyChecks with TestActorSystem with TestSourceProvider {

  val sut                                    = new MessageTypeExtractorImpl
  val messageTypeGenerator: Gen[MessageType] = Gen.oneOf(MessageType.values.toSeq)

  def validBody(mt: MessageType): Source[ByteString, _] =
    Source.single(
      ByteString(s"""<ncts:${mt.rootNode.toUpperCase} PhaseID="NCTS5.0" xmlns:ncts="http://ncts.dgtaxud.ec"><blah></blah></ncts:${mt.rootNode.toUpperCase}>""")
    )

  "extractFromHeader" - {
    "returns Left(UnableToExtract) when no X-Message-Type header" in {
      whenReady(sut.extractFromHeaders(Headers()).value) {
        _ mustBe Left(UnableToExtractFromHeader)
      }
    }

    "returns Left(InvalidMessageType) when X-Message-Type header value is nonsense" in {
      whenReady(sut.extractFromHeaders(Headers("X-Message-Type" -> "abcde")).value) {
        _ mustBe Left(InvalidMessageType("abcde"))
      }
    }

    "returns Right(MessageType)" in forAll(messageTypeGenerator) {
      mt =>
        whenReady(sut.extractFromHeaders(Headers("X-Message-Type" -> mt.code)).value) {
          _ mustBe Right(mt)
        }
    }
  }

  "extractFromBody" - {
    "return Left(UnableToExtract) when the body is not as expected" in {
      whenReady(sut.extractFromBody(Source.single(ByteString("nope"))).value) {
        _ mustBe Left(UnableToExtractFromBody)
      }
    }

    "return Left(InvalidMessageType) when the root name is not as expected" in {
      whenReady(sut.extractFromBody(Source.single(ByteString("<CC001C></CC001C>"))).value) {
        _ mustBe Left(InvalidMessageType("CC001C"))
      }
    }

    "returns Right(MessageType)" in forAll(messageTypeGenerator) {
      mt =>
        whenReady(sut.extractFromBody(validBody(mt)).value) {
          _ mustBe Right(mt)
        }
    }

    "return Left(UnableToExtract) when no body is returned (i.e., if the wrapping is incorrect)" in {
      whenReady(sut.extractFromBody(Source.empty[ByteString]).value) {
        _ mustBe Left(UnableToExtractFromBody)
      }
    }
  }

  "extract" - {
    "should choose the X-Message-Type first" in forAll(messageTypeGenerator) {
      mt: MessageType =>
        whenReady(sut.extract(Headers("X-Message-Type" -> mt.code), Source.single(ByteString("<CC001C></CC001C>"))).value) {
          _ mustBe Right(mt)
        }
    }

    "should try the body if the header does not contain a valid type" in forAll(messageTypeGenerator) {
      mt: MessageType =>
        whenReady(sut.extract(Headers("X-Message-Type" -> "nope"), validBody(mt)).value) {
          _ mustBe Right(mt)
        }
    }

    "should fail with the body's error if the header and the body error out" in {
      whenReady(sut.extract(Headers("X-Message-Type" -> "nope"), Source.single(ByteString("nope"))).value) {
        _ mustBe Left(UnableToExtractFromBody)
      }
    }

    "should fail with the unexpected if the stream is in a weird state" in {
      val error = new IllegalStateException()
      whenReady(sut.extract(Headers("X-Message-Type" -> "nope"), Source.failed(error)).value) {
        _ mustBe Left(Unexpected(Some(error)))
      }
    }
  }
}
