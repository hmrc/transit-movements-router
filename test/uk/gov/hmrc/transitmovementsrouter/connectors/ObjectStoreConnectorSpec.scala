package uk.gov.hmrc.transitmovementsrouter.connectors

import cats.data.EitherT
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchersSugar.any
import org.mockito.ArgumentMatchersSugar.eqTo
import org.mockito.Mockito.when
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import play.api.mvc.Result
import play.api.mvc.Results
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.SessionId
import uk.gov.hmrc.objectstore.client.Path.File
import uk.gov.hmrc.objectstore.client.Md5Hash
import uk.gov.hmrc.objectstore.client.ObjectMetadata
import uk.gov.hmrc.objectstore.client.ObjectSummaryWithMd5
import uk.gov.hmrc.objectstore.client.RetentionPeriod
import uk.gov.hmrc.objectstore.client.http.ObjectStoreContentWrite
import uk.gov.hmrc.transitmovementsrouter.models.MessageId
import uk.gov.hmrc.transitmovementsrouter.models.MovementId

import java.nio.file.Paths
import java.time.Instant
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class ObjectStoreConnectorSpec extends AnyFreeSpec with Matchers with ScalaFutures with MockitoSugar {

  trait PlayObjectStoreClientEither {

    def putObject(
      file: File,
      ioFile: java.io.File,
      retentionPeriod: RetentionPeriod,
      maybeContentEncoding: Option[String],
      maybeContentMd5: Option[Md5Hash]
    )(
      contentWriter: ObjectStoreContentWrite[_, _, _],
      hc: HeaderCarrier
    ): EitherT[Future, Result, ObjectSummaryWithMd5]
  }

  private val mockClient = mock[PlayObjectStoreClientEither]
  private val sessionId  = SessionId("session-Id")

  implicit val hc: HeaderCarrier = HeaderCarrier(sessionId = Some(sessionId))

  private val objectStoreConnector = new ObjectStoreConnector(mockClient)
  import uk.gov.hmrc.objectstore.client.Path.Directory
  import uk.gov.hmrc.objectstore.client.Path.File

  private val movementId = MovementId("1")
  private val messageId  = MessageId("1")
  private val path       = Paths.get("test.xml")
  val directory          = Directory("/")
  val file               = File(directory, path.toFile.getName)

  private val metadata = ObjectMetadata(
    contentType = "application/xml",
    contentLength = path.toFile.length(),
    contentMd5 = new Md5Hash("md5-hash-string"),
    lastModified = Instant.now(),
    userMetadata = Map.empty[String, String]
  )

  "ObjectStoreConnector" - {

    "upload" - {

      "should return a Right when the upload is successful" in {
        val expectedObjectSummary = ObjectSummaryWithMd5(file, path.toFile.length(), new Md5Hash("md5-hash-string"), Instant.now())
        when(
          mockClient.putObject(
            eqTo(file),
            any[java.io.File],
            any[RetentionPeriod],
            any[Option[String]],
            any[Option[Md5Hash]]
          )(any[ObjectStoreContentWrite[_, _, _]], any[HeaderCarrier])
        ).thenReturn(EitherT.rightT[Future, Result](expectedObjectSummary))
        val result: EitherT[Future, Result, ObjectSummaryWithMd5] = objectStoreConnector.upload(movementId, messageId, path)(hc)

        whenReady(result.value) {
          actualObjectSummary =>
            actualObjectSummary shouldEqual Right(expectedObjectSummary)
        }
      }

      "should return a Left when the upload fails" in {
        val expectedErrorResult = Results.BadRequest("Bad Request")
        when(
          mockClient.putObject(
            eqTo(file),
            any[java.io.File],
            any[RetentionPeriod],
            any[Option[String]],
            any[Option[Md5Hash]]
          )(any[ObjectStoreContentWrite[_, _, _]], any[HeaderCarrier])
        ).thenReturn(EitherT.leftT[Future, ObjectSummaryWithMd5](expectedErrorResult))

        val result: EitherT[Future, Result, ObjectSummaryWithMd5] = objectStoreConnector.upload(movementId, messageId, path)(hc)

        whenReady(result.value) {
          actualResult =>
            actualResult shouldBe Left(Results.BadRequest("Bad Request"))
        }
      }
    }
  }
}
