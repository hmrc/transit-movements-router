package uk.gov.hmrc.transitmovementsrouter.models.errors

import uk.gov.hmrc.transitmovementsrouter.models.MovementId

sealed trait PushNotificationError

object PushNotificationError {
  case class MovementNotFound(movementId: MovementId) extends PushNotificationError
  case class Unexpected(exception: Option[Throwable]) extends PushNotificationError
}
