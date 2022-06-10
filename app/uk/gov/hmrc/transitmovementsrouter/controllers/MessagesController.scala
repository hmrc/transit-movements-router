package uk.gov.hmrc.transitmovementsrouter.controllers

import akka.stream.Materializer
import play.api.libs.Files.TemporaryFileCreator
import play.api.mvc.ControllerComponents
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController
import uk.gov.hmrc.transitmovementsrouter.controllers.streams.StreamingParsers
import uk.gov.hmrc.transitmovementsrouter.models.EORINumber

import javax.inject.Inject
import scala.concurrent.Future

class MessagesController @Inject() (cc: ControllerComponents, val temporaryFileCreator: TemporaryFileCreator)(implicit val materializer: Materializer)
    extends BackendController(cc)
    with StreamingParsers
    with TemporaryFiles {

  def post(eori: EORINumber, movementType: String, movementId: String, messageId: String) = Action.async(
    streamFromFile
  ) {
    implicit request =>
      Future.successful(Ok("Hello world"))
  }

}
