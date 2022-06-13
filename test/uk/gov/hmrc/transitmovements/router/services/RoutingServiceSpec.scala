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

import akka.NotUsed
import akka.stream.IOResult
import akka.stream.scaladsl.FileIO
import akka.stream.scaladsl.Source
import akka.util.ByteString
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import play.api.libs.Files
import play.api.libs.Files.SingletonTemporaryFileCreator
import uk.gov.hmrc.transitmovementsrouter.services.RoutingService

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.xml.NodeSeq

class RoutingServiceSpec extends AnyFreeSpec with Matchers {

//  "submitMessage" - {
//    "returns Valid Message if it has a rootNode" in {
//
//      val result = RoutingServiceImpl().submitMessage(Source.single(ByteString(cc015c.toString())), "ID0001")
//
//      result mustBe NotUsed
//
//    }
//  }

  val cc015c: NodeSeq =
    <CC015C>
      <messageSender>ABC1234</messageSender>
      <preparationDateAndTime>2022-05-25T09:37:04</preparationDateAndTime>
    </CC015C>

}
