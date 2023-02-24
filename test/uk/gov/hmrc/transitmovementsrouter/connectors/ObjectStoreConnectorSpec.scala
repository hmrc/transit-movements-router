package uk.gov.hmrc.transitmovementsrouter.connectors

import cats.data.EitherT
import cats.implicits._
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.{eq => eqTo}
import org.mockito.Mockito.verify
import org.mockito.Mockito.when
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import play.api.mvc.Result
import play.api.mvc.Results
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.objectstore.client.Path.Directory
import uk.gov.hmrc.objectstore.client._
import uk.gov.hmrc.transitmovementsrouter.models.MessageId
import uk.gov.hmrc.transitmovementsrouter.models.MovementId

import java.io.{File => JFile}
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

class ObjectStoreConnectorSpec extends AnyFreeSpec with Matchers with ScalaFutures with MockitoSugar {

  val testPath: Path  = Files.createTempFile("test", ".xml")
  val testFile: JFile = testPath.toFile

  trait Test {
    val testFile: JFile = testPath.toFile

    val mockClient: PlayObjectStoreClientEither[Future] = mock[PlayObjectStoreClientEither[Future]]
    val connector                                       = new ObjectStoreConnector(mockClient)
    val hc                                              = HeaderCarrier()
    val movementId                                      = MovementId("123")
    val messageId                                       = MessageId("abc")
    val testPath: Path                                  = Files.createTempFile("test", ".xml")

    def verifyInternalServerError(result: EitherT[Future, Result, ObjectSummaryWithMd5]): Unit =
      result.leftMap(
        _ => Results.InternalServerError("")
      )
  }

  "ObjectStoreConnector" - {

    "upload" - {

      "returns a successful result when the call to putObject succeeds" in new Test {

        when(
          mockClient.putObject[Array[Byte]](
            Path.File(Directory("movements/123/messages"), "abc.xml"),
            testPath.toFile,
            RetentionPeriod.OneMonth,
            Some("application/xml"),
            Some(new Md5Hash("md5-hash-string"))
          )(any[ExecutionContext], any[HeaderCarrier])
        ).thenReturn(
          EitherT.rightT[Future, Throwable](
            ObjectSummaryWithMd5(
              Path.File(Directory("movements/123/messages"), "abc.xml"),
              testPath.toFile.length(),
              new Md5Hash("md5-hash-string"),
              Instant.now()
            )
          )
        )

        val result = connector.upload(movementId, messageId, testPath)(hc)
        result.value.futureValue shouldBe a[Right[_, _]]

        verify(
          mockClient
        ).putObject[Array[Byte]](
          Path.File(Directory("movements/123/messages"), "abc.xml"),
          testPath.toFile,
          RetentionPeriod.OneMonth,
          Some("application/xml"),
          Some(new Md5Hash("md5-hash-string"))
        )(any[ExecutionContext], eqTo(hc))

      }

      "returns an InternalServerError when the call to putObject fails" in new Test {

        when(
          mockClient.putObject[Array[Byte]](
            any[Path.File],
            any[JFile],
            any[RetentionPeriod],
            any[Option[String]],
            any[Option[Md5Hash]]
          )(any[ExecutionContext], any[HeaderCarrier])
        ).thenReturn(
          EitherT.leftT[Future, ObjectSummaryWithMd5](
            Results.InternalServerError("")
          )
        )

        val result = connector.upload(movementId, messageId, testPath)(hc)
        result.value.futureValue shouldBe a[Left[_, _]]

        verifyInternalServerError(result)
      }
    }
  }
}
