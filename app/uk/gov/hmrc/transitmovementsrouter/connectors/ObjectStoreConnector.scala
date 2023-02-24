package uk.gov.hmrc.transitmovementsrouter.connectors

import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.google.inject.ImplementedBy
import play.api.Logging
import play.api.mvc.Result
import play.api.mvc.Results
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.UpstreamErrorResponse
import uk.gov.hmrc.objectstore.client.ObjectSummaryWithMd5
import uk.gov.hmrc.objectstore.client.Path
import uk.gov.hmrc.objectstore.client.play.Implicits._
import uk.gov.hmrc.objectstore.client.play.PlayObjectStoreClientEither
import uk.gov.hmrc.transitmovementsrouter.models.MessageId
import uk.gov.hmrc.transitmovementsrouter.models.MovementId

import javax.inject.Inject
import scala.concurrent.ExecutionContext
import scala.concurrent.Future



@ImplementedBy(classOf[ObjectStoreConnectorImpl])
trait ObjectStoreConnector {

  def putObjectStoreFile(movementId: MovementId, messageId: MessageId, path: java.nio.file.Path)(implicit
                                                                                                 ec: ExecutionContext,
                                                                                                 hc: HeaderCarrier
  ): Future[Either[Result, ObjectSummaryWithMd5]]

}


class ObjectStoreConnectorImpl @Inject() (client: PlayObjectStoreClientEither)(implicit ec: ExecutionContext) extends ObjectStoreConnector with Logging {

  override def putObjectStoreFile(movementId: MovementId, messageId: MessageId, path: java.nio.file.Path)(implicit
                                                                                                 ec: ExecutionContext,
                                                                                                 hc: HeaderCarrier
  ): Future[Either[Result, ObjectSummaryWithMd5]] = {
    client
      .putObject(
        path = Path.Directory(s"movements/${movementId.value}/messages").file(s"${messageId.value}.xml"),
        content = path.toFile,
        contentType = Some("application/xml")
      )
      .map {
        case Right(summary) => Right(summary)
        case Left(exception) =>
          logger.error(s"An error has occurred: ${exception.getMessage}", exception)
          Left(Results.InternalServerError("An error has occurred."))
      }
      .recover {
        case e: UpstreamErrorResponse =>
          logger.error(s"Upstream error: ${e.getMessage}")
          Left(Results.InternalServerError("Upstream error encountered"))
        case e: Exception =>
          logger.error(s"An error has occurred: ${e.getMessage}", e)
          Left(Results.InternalServerError("An error has occurred."))
      }
  }

}

