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
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import uk.gov.hmrc.transitmovementsrouter.base.TestActorSystem

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Failure
import scala.util.Success
import scala.xml.XML

class EISMessageTransformerSpec extends AnyFreeSpec with Matchers with ScalaFutures with TestActorSystem {

  val sut = new EISMessageTransformersImpl

  "unwrap" - {

    "should successfully transform valid xml" in {
      val input =
        """<n1:TraderChannelResponse xmlns:txd="http://ncts.dgtaxud.ec" xmlns:n1="http://www.hmrc.gov.uk/eis/ncts5/v1" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://www.hmrc.gov.uk/eis/ncts5/v1 EIS_WrapperV10_TraderChannelSubmission-51.8.xsd"><txd:CC029C PhaseID="NCTS5.0">text</txd:CC029C></n1:TraderChannelResponse>"""

      val result = Source
        .single(ByteString.fromString(input))
        .via(sut.unwrap)
        .runReduce(_ ++ _)
        .map {
          x => x.utf8String
        }

      whenReady(result) {
        r => XML.loadString(r) mustBe <ncts:CC029C xmlns:ncts="http://ncts.dgtaxud.ec" PhaseID="NCTS5.0">text</ncts:CC029C>
      }
    }

    "should successfully transform with more complex valid xml, without specifying the PhaseID" in {
      val input =
        <n1:TraderChannelResponse xmlns:txd="http://ncts.dgtaxud.ec" xmlns:n1="http://www.hmrc.gov.uk/eis/ncts5/v1" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://www.hmrc.gov.uk/eis/ncts5/v1 EIS_WrapperV10_TraderChannelSubmission-51.8.xsd">
          <txd:CC015C>
            <messageSender>sender</messageSender>
            <messageReceiver>receiver</messageReceiver>
            <something>else</something>
          </txd:CC015C>
        </n1:TraderChannelResponse>.mkString

      val result = Source
        .single(ByteString.fromString(input))
        .via(sut.unwrap)
        .runReduce(_ ++ _)
        .map {
          x => x.utf8String
        }

      whenReady(result) {
        r =>
          XML.loadString(r) mustBe <ncts:CC015C xmlns:ncts="http://ncts.dgtaxud.ec" PhaseID="NCTS5.1">
            <messageSender>sender</messageSender>
            <messageReceiver>receiver</messageReceiver>
            <something>else</something>
          </ncts:CC015C>
      }
    }

    "fail when xml isn't valid with no namespaces" in {
      val input = "<Wrapper>text</Wrapper>"

      val result =
        Source
          .single(ByteString.fromString(input))
          .via(sut.unwrap)
          .runWith(Sink.head)

      whenReady(result.transform {
        case Success(_) => Failure(fail())
        case Failure(x) => Success(x)
      }) {
        res => res mustBe a[Throwable]
      }

    }

    "fail when xml isn't valid with inner namespaces" in {
      val input = """<Wrapper><ncts:CC029C xmlns:ncts="http://ncts.dgtaxud.ec">text</ncts:CC029C></Wrapper>"""

      val result =
        Source
          .single(ByteString.fromString(input))
          .via(sut.unwrap)
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
