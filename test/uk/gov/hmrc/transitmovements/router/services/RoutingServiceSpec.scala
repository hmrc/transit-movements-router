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
