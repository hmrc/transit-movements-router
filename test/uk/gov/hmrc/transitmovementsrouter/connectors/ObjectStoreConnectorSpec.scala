package uk.gov.hmrc.transitmovementsrouter.connectors

import cats.data.EitherT
import cats.implicits._
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.when
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import play.api.mvc.Results
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.objectstore.client._
import uk.gov.hmrc.transitmovementsrouter.models.{MessageId, MovementId}

import java.io.File
import java.nio.file.{Files, Path}
import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}

class ObjectStoreConnectorSpec extends AnyFreeSpec with Matchers with ScalaFutures with MockitoSugar {

  trait Test {
    implicit val ec = scala.concurrent.ExecutionContext.global

    val mockClient: PlayObjectStoreClientEither[Future] = mock[PlayObjectStoreClientEither[Future]]
    val connector = new ObjectStoreConnector(mockClient)
    val hc = HeaderCarrier()
    val movementId = MovementId("123")
    val messageId = MessageId("abc")
    val testPath: Path = Files.createTempFile("test", "xml")

    def verifyInternalServerError(result: EitherT[Future, Results.Status, ObjectSummaryWithMd5]): Unit = {
      result.leftMap(_ => Results.InternalServerError(""))
      result.value.futureValue.left.map { res =>
        res.header.status shouldBe Results.InternalServerError.header.status
      }
    }
  }

  "ObjectStoreConnector" - {

    "upload" - {

      "returns a successful result when the call to putObject succeeds" in new Test {

        when(
          mockClient.putObject[Array[Byte]](
            any(),
            any(),
            any(),
            any(),
            any()
          )(any(), any())(any())
        ).thenReturn(
          EitherT.rightT[Future, Throwable](
            ObjectSummaryWithMd5(
              "",
              "",
              new Md5Hash("md5-hash-string"),
              "application/xml",
              testPath.toFile.length(),
              RetentionPeriod.OneMonth,
              Instant.now()
            )
          )
        )

        val result = connector.upload(movementId, messageId, testPath)(hc)
        result.value.futureValue shouldBe a[Right[_, _]]

        verify(
          mockClient
        ).putObject[Array[Byte]](
          Path.File(Path.Directory(s"movements/${movementId.value}/messages"), s"${messageId.value}.xml"),
          testPath.toFile,
          RetentionPeriod.OneMonth,
          Some("application/xml"),
          Some(new Md5Hash("md5-hash-string"))
        )(any(), eqTo(hc))(any())

      }

      "returns an InternalServerError when the call to putObject fails" in new Test {

        when(
          mockClient.putObject[Array[Byte]](
            any(),
            any(),
            any(),
            any(),
            any()
          )(any(), any())(any())
        ).thenReturn(
          EitherT.leftT[Future, ObjectSummaryWithMd5](
            new RuntimeException("Something went wrong")
          )
        )

        val result = connector.upload(movementId, messageId, testPath)(hc)
        result.value.futureValue shouldBe a[Left[_, _]]

        verifyInternalServerError(result)

      }
    }
  }
}
