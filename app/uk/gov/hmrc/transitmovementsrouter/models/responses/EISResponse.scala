package uk.gov.hmrc.transitmovementsrouter.models.responses

import play.api.libs.json.Json

import java.time.OffsetDateTime

case class EISResponse(message: String, timestamp: OffsetDateTime, path: String) {
  def invalidAccessCode = message == "Not Valid Access Code for this operation"
  def invalidGRN        = message.contains("Guarantee not found for GRN")
}

object EISResponse {
  implicit val format = Json.format[EISResponse]
}
