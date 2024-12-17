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

package uk.gov.hmrc.transitmovementsrouter.models

import play.api.libs.json.JsError
import play.api.libs.json.JsString
import play.api.libs.json.JsSuccess
import play.api.libs.json.Reads
import play.api.libs.json.Writes

sealed trait MessageType extends Product with Serializable {
  def code: String

  def rootNode: String

  def auditType: Option[AuditType] = None

  def movementType: MovementType
}

sealed trait ArrivalMessageType extends MessageType {
  val movementType: MovementType = MovementType.Arrival
}

sealed trait DepartureMessageType extends MessageType {
  val movementType: MovementType = MovementType.Departure
}

sealed trait RequestMessageType extends MessageType {
  val officeNode: String
}

sealed trait ResponseMessageType extends MessageType

sealed abstract class DepartureRequestMessageType(
  val code: String,
  val rootNode: String,
  val officeNode: String = "CustomsOfficeOfDeparture"
) extends RequestMessageType
    with DepartureMessageType

sealed abstract class DepartureResponseMessageType(
  val code: String,
  val rootNode: String,
  override val auditType: Option[AuditType]
) extends ResponseMessageType
    with DepartureMessageType

sealed abstract class ArrivalRequestMessageType(
  val code: String,
  val rootNode: String,
  override val officeNode: String = "CustomsOfficeOfDestinationActual"
) extends RequestMessageType
    with ArrivalMessageType

sealed abstract class ArrivalResponseMessageType(
  val code: String,
  val rootNode: String,
  override val auditType: Option[AuditType]
) extends ResponseMessageType
    with ArrivalMessageType

object MessageType {
  // *******************
  // Departures Requests
  // *******************

  /** E_DEC_AMD (IE013) */
  case object DeclarationAmendment extends DepartureRequestMessageType("IE013", "CC013C")

  /** E_DEC_DAT (IE015) */
  case object DeclarationData extends DepartureRequestMessageType("IE015", "CC015C")

  /** E_DEC_INV (IE014) */
  private case object DeclarationInvalidation extends DepartureRequestMessageType("IE014", "CC014C")

  /** E_PRE_NOT (IE170) */
  private case object PresentationNotification extends DepartureRequestMessageType("IE170", "CC170C")

  val departureRequestValues: Set[DepartureRequestMessageType] = Set(
    DeclarationAmendment,
    DeclarationInvalidation,
    DeclarationData,
    PresentationNotification
  )

  // ********************
  // Departures Responses
  // ********************

  /** E_AMD_ACC (IE004) */
  case object AmendmentAcceptance extends DepartureResponseMessageType("IE004", "CC004C", Some(AuditType.AmendmentAcceptance))

  /** E_DEP_REJ (IE056) */
  private case object DepartureOfficeRejection extends DepartureResponseMessageType("IE056", "CC056C", Some(AuditType.RejectionFromOfficeOfDeparture))

  /** E_INV_DEC (IE009) */
  case object InvalidationDecision extends DepartureResponseMessageType("IE009", "CC009C", Some(AuditType.InvalidationDecision))

  /** E_GUA_INV (IE055) */
  private case object GuaranteeInvalid extends DepartureResponseMessageType("IE055", "CC055C", Some(AuditType.GuaranteeNotValid))

  /** E_DIS_SND (IE019) */
  case object Discrepancies extends DepartureResponseMessageType("IE019", "CC019C", Some(AuditType.Discrepancies))

  /** E_POS_ACK (IE928) */
  case object PositiveAcknowledge extends DepartureResponseMessageType("IE928", "CC928C", Some(AuditType.PositiveAcknowledge))

  /** E_MRN_ALL (IE028) */
  case object MrnAllocated extends DepartureResponseMessageType("IE028", "CC028C", Some(AuditType.MRNAllocated))

  /** E_REL_TRA (IE029) */
  case object ReleaseForTransit extends DepartureResponseMessageType("IE029", "CC029C", Some(AuditType.ReleaseForTransit))

  /** E_WRT_NOT (IE045) */
  case object WriteOffNotification extends DepartureResponseMessageType("IE045", "CC045C", Some(AuditType.WriteOffNotification))

  /** E_REL_NOT (IE051) */
  case object NoReleaseForTransit extends DepartureResponseMessageType("IE051", "CC051C", Some(AuditType.NoReleaseForTransit))

  /** E_CTR_DEC (IE060) */
  case object ControlDecisionNotification extends DepartureResponseMessageType("IE060", "CC060C", Some(AuditType.ControlDecisionNotification))

  /** E_AMD_NOT (IE022) */
  private case object NotificationToAmend extends DepartureResponseMessageType("IE022", "CC022C", Some(AuditType.NotificationToAmendDeclaration))

  /** E_INC_NOT (IE182) */
  private case object IncidentNotification extends DepartureResponseMessageType("IE182", "CC182C", Some(AuditType.ForwardedIncidentNotificationToED))

  /** E_REC_NOT (IE035) */
  case object RecoveryNotification extends DepartureResponseMessageType("IE035", "CC035C", Some(AuditType.RecoveryNotification))

  val departureResponseValues: Set[DepartureResponseMessageType] = Set(
    AmendmentAcceptance,
    DepartureOfficeRejection,
    InvalidationDecision,
    GuaranteeInvalid,
    Discrepancies,
    PositiveAcknowledge,
    MrnAllocated,
    ReleaseForTransit,
    WriteOffNotification,
    NoReleaseForTransit,
    ControlDecisionNotification,
    NotificationToAmend,
    IncidentNotification,
    RecoveryNotification
  )

  private val departureValues = departureRequestValues ++ departureResponseValues

  // ****************
  // Arrival Requests
  // ****************

  /** E_REQ_REL (IE054) */
  case object ArrivalNotification extends ArrivalRequestMessageType("IE007", "CC007C")

  /** E_PRE_NOT (IE170) */
  case object UnloadingRemarks extends ArrivalRequestMessageType("IE044", "CC044C")

  val arrivalRequestValues: Set[ArrivalRequestMessageType] = Set(
    ArrivalNotification,
    UnloadingRemarks
  )

  // ****************
  // Arrival Response
  // ****************

  /** E_DES_REJ (IE057) */
  private case object DestinationOfficeRejection extends ArrivalResponseMessageType("IE057", "CC057C", Some(AuditType.RejectionFromOfficeOfDestination))

  /** E_GDS_REL (IE025) */
  case object GoodsReleaseNotification extends ArrivalResponseMessageType("IE025", "CC025C", Some(AuditType.GoodsReleaseNotification))

  /** E_ULD_PER (IE025) */
  case object UnloadingPermission extends ArrivalResponseMessageType("IE043", "CC043C", Some(AuditType.UnloadingPermission))

  val arrivalResponseValues: Set[ArrivalResponseMessageType] = Set(
    DestinationOfficeRejection,
    GoodsReleaseNotification,
    UnloadingPermission
  )

  private val arrivalValues = arrivalRequestValues ++ arrivalResponseValues

  val requestValues: Set[RequestMessageType] = arrivalRequestValues ++ departureRequestValues

  val responseValues: Set[ResponseMessageType] = arrivalResponseValues ++ departureResponseValues

  val values: Set[MessageType] = arrivalValues ++ departureValues

  def withCode(name: String): Option[MessageType] =
    values.find(_.code == name)

  private def findByCode(code: String): Option[MessageType] =
    values.find(_.code == code)

  implicit val messageTypeReads: Reads[MessageType] = Reads {
    case JsString(value) => findByCode(value).map(JsSuccess(_)).getOrElse(JsError())
    case _               => JsError()
  }

  implicit val messageTypeWrites: Writes[MessageType] = Writes {
    obj => JsString(obj.code)
  }

}
