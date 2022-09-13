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

package uk.gov.hmrc.transitmovementsrouter.services

import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.util.ByteString
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import uk.gov.hmrc.transitmovementsrouter.base.TestActorSystem

import scala.concurrent.ExecutionContext.Implicits.global
import scala.reflect.runtime.universe.Try
import scala.util.Failure
import scala.util.Success

class StreamingMessageTrimmerSpec extends AnyFreeSpec with Matchers with ScalaFutures with TestActorSystem {
  "trim flow should" - {

    val sut = new StreamingMessageTrimmerImpl

    "successfully trim with valid xml" in {
      val input = "<TraderChannelResponse><test>text</test></TraderChannelResponse>"

      val result = sut
        .trim(Source.single(ByteString.fromString(input)))
        .runReduce(_ ++ _)
        .map {
          x => x.utf8String
        }

      whenReady(result) {
        r => r mustBe "<test>text</test>"
      }
    }

    "fail when xml isn't valid" in {
      val input = "<Wrapper>text</Wrapper>"

      val result =
        sut
          .trim(Source.single(ByteString.fromString(input)))
          .runWith(Sink.head)

      whenReady(result.transform {
        case Success(_) => Failure(fail())
        case Failure(x) => Success(x)
      }) {
        res => res mustBe a[Throwable]
      }

    }

  }
}
