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

package uk.gov.hmrc.transitmovementsrouter.connectors

import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.stream.scaladsl.FileIO
import akka.stream.scaladsl.Sink
import akka.util.ByteString
import org.scalatest.BeforeAndAfterAll
import org.scalatest.OptionValues
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.mvc.Result
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.objectstore.client.ObjectSummaryWithMd5
import uk.gov.hmrc.transitmovementsrouter.models.MessageId
import uk.gov.hmrc.transitmovementsrouter.models.MovementId

import java.nio.file.Files
import java.nio.file.Paths
import scala.concurrent.Future

class ObjectStoreConnectorIntegrationSpec
    extends AnyWordSpec
    with Matchers
    with BeforeAndAfterAll
    with ScalaFutures
    with OptionValues
    with GuiceOneAppPerSuite {

  implicit val system: ActorSystem        = ActorSystem("ObjectStoreConnectorIntegrationSpec")
  implicit val materializer: Materializer = Materializer(system)

  private val connector: ObjectStoreConnector = app.injector.instanceOf[ObjectStoreConnector]

  override def afterAll(): Unit = {
    materializer.shutdown()
    system.terminate()
  }

  "ObjectStoreConnector" should {
    "upload and retrieve a file" in {
      val movementId  = MovementId("testMovementId")
      val messageId   = MessageId("testMessageId")
      val testData    = "test data"
      val filePath    = Paths.get("test.xml")
      val fileContent = ByteString(testData)

      // Upload the file
      val result: Future[Either[Result, ObjectSummaryWithMd5]] = connector.upload(movementId, messageId, filePath)(
        HeaderCarrier()
      )

      // Check that the upload was successful
      result.futureValue shouldBe Right(filePath.toString)

      // Retrieve the file
      val result2 = connector.get(movementId, messageId)(HeaderCarrier())

      // Check that the retrieval was successful
      val tempFile = Files.createTempFile("prefix", "suffix").toFile
      result2.futureValue shouldBe Right(FileIO.fromPath(tempFile.toPath).runWith(Sink.seq).futureValue)

      // Check that the temporary file was created
      tempFile.exists() shouldBe true
    }
  }
}
