/*
 * Copyright 2022 HM Revenue & Customs
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

sealed trait MessageType extends Product with Serializable {
  def code: String
  def rootNode: String
}

sealed trait ArrivalMessageType extends MessageType

sealed trait DepartureMessageType extends MessageType

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
  val rootNode: String
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
  val rootNode: String
) extends ResponseMessageType
    with ArrivalMessageType

sealed abstract class ErrorMessageType(val code: String, val rootNode: String, val xsdPath: String)
    extends ResponseMessageType
    with ArrivalMessageType
    with DepartureMessageType

object MessageType {
  // *******************
  // Departures Requests
  // *******************

  /** E_DEC_AMD (IE013) */
  case object DeclarationAmendment extends DepartureRequestMessageType("IE013", "CC013C")

  /** E_DEC_INV (IE014) */
  case object DeclarationInvalidation extends DepartureRequestMessageType("IE014", "CC014C")

  /** E_DEC_DAT (IE015) */
  case object DeclarationData extends DepartureRequestMessageType("IE015", "CC015C")

  /** E_REQ_REL (IE054) */
  case object RequestOfRelease extends DepartureRequestMessageType("IE054", "CC054C")

  /** E_PRE_NOT (IE170) */
  case object PresentationNotification extends DepartureRequestMessageType("IE170", "CC170C")

  case object InformationAboutNonArrivedMovement extends DepartureRequestMessageType("IE141", "CC141C", "CustomsOfficeOfEnquiryAtDeparture")

  val departureRequestValues = Set(
    DeclarationAmendment,
    DeclarationInvalidation,
    DeclarationData,
    RequestOfRelease,
    PresentationNotification,
    InformationAboutNonArrivedMovement
  )

  // ********************
  // Departures Responses
  // ********************

  /** E_AMD_ACC (IE004) */
  case object AmendmentAcceptance extends DepartureResponseMessageType("IE004", "CC004C")

  /** E_DEP_REJ (IE056) */
  case object DepartureOfficeRejection extends DepartureResponseMessageType("IE056", "CC056C")

  /** E_INV_DEC (IE009) */
  case object InvalidationDecision extends DepartureResponseMessageType("IE009", "CC009C")

  /** E_GUA_INV (IE055) */
  case object GuaranteeInvalid extends DepartureResponseMessageType("IE055", "CC055C")

  /** E_DIS_SND (IE019) */
  case object Discrepancies extends DepartureResponseMessageType("IE019", "CC019C")

  /** E_POS_ACK (IE928) */
  case object PositiveAcknowledge extends DepartureResponseMessageType("IE928", "CC928C")

  /** E_MRN_ALL (IE028) */
  case object MrnAllocated extends DepartureResponseMessageType("IE028", "CC028C")

  /** E_REL_TRA (IE029) */
  case object ReleaseForTransit extends DepartureResponseMessageType("IE029", "CC029C")

  /** E_WRT_NOT (IE045) */
  case object WriteOffNotification extends DepartureResponseMessageType("IE045", "CC045C")

  /** E_REL_NOT (IE051) */
  case object NoReleaseForTransit extends DepartureResponseMessageType("IE051", "CC051C")

  /** E_CTR_DEC (IE060) */
  case object ControlDecisionNotification extends DepartureResponseMessageType("IE060", "CC060C")

  /** E_AMD_NOT (IE022) */
  case object NotificationToAmend extends DepartureResponseMessageType("IE022", "CC022C")

  /** E_INC_NOT (IE182) */
  case object IncidentNotification extends DepartureResponseMessageType("IE182", "CC182C")

  /** E_REC_NOT (IE035) */
  case object RecoveryNotification extends DepartureResponseMessageType("IE035", "CC035C")

  case object RequestOnNonArrivedMovement extends DepartureResponseMessageType("IE140", "CC140C")

  val departureResponseValues = Set(
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
    RecoveryNotification,
    RequestOnNonArrivedMovement
  )

  val departureValues = departureRequestValues ++ departureResponseValues

  // ****************
  // Arrival Requests
  // ****************

  /** E_REQ_REL (IE054) */
  case object ArrivalNotification extends ArrivalRequestMessageType("IE007", "CC007C")

  /** E_PRE_NOT (IE170) */
  case object UnloadingRemarks extends ArrivalRequestMessageType("IE044", "CC044C")

  val arrivalRequestValues = Set(
    ArrivalNotification,
    UnloadingRemarks
  )

  // ****************
  // Arrival Response
  // ****************

  /** E_DES_REJ (IE057) */
  case object DestinationOfficeRejection extends ArrivalResponseMessageType("IE057", "CC057C")

  /** E_GDS_REL (IE025) */
  case object GoodsReleaseNotification extends ArrivalResponseMessageType("IE025", "CC025C")

  /** E_ULD_PER (IE025) */
  case object UnloadingPermission extends ArrivalResponseMessageType("IE043", "CC043C")

  val arrivalResponseValues = Set(
    DestinationOfficeRejection,
    GoodsReleaseNotification,
    UnloadingPermission
  )

  val arrivalValues = arrivalRequestValues ++ arrivalResponseValues

  // ***************
  // Error Responses
  // ***************

  case object XmlNack extends ErrorMessageType("IE917", "CC917C", "/xsd/CC917C.xsd")

  val errorValues = Set(XmlNack)

  val requestValues = arrivalRequestValues ++ departureRequestValues

  val responseValues = arrivalResponseValues ++ departureResponseValues

  val values = arrivalValues ++ departureValues ++ errorValues

  def withCode(name: String): Option[MessageType] =
    values.find(_.code == name)

  def isRequestMessageType(message: MessageType): Boolean =
    message match {
      case _: RequestMessageType => true
      case _                     => false
    }

}
