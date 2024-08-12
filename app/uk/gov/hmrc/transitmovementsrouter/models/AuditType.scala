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

sealed abstract class AuditType(val name: String)

object AuditType {

  final case object AmendmentAcceptance                 extends AuditType("AmendmentAcceptance")
  final case object InvalidationDecision                extends AuditType("InvalidationDecision")
  final case object Discrepancies                       extends AuditType("Discrepancies")
  final case object NotificationToAmendDeclaration      extends AuditType("NotificationToAmendDeclaration")
  final case object GoodsReleaseNotification            extends AuditType("GoodsReleaseNotification")
  final case object MRNAllocated                        extends AuditType("MRNAllocated")
  final case object ReleaseForTransit                   extends AuditType("ReleaseForTransit")
  final case object RecoveryNotification                extends AuditType("RecoveryNotification")
  final case object UnloadingPermission                 extends AuditType("UnloadingPermission")
  final case object WriteOffNotification                extends AuditType("WriteOffNotification")
  final case object NoReleaseForTransit                 extends AuditType("NoReleaseForTransit")
  final case object GuaranteeNotValid                   extends AuditType("GuaranteeNotValid")
  final case object RejectionFromOfficeOfDeparture      extends AuditType("RejectionFromOfficeOfDeparture")
  final case object RejectionFromOfficeOfDestination    extends AuditType("RejectionFromOfficeOfDestination")
  final case object ControlDecisionNotification         extends AuditType("ControlDecisionNotification")
  final case object PositiveAcknowledge                 extends AuditType("PositiveAcknowledge")
  final case object NCTSRequestedMissingMovement        extends AuditType("NCTSRequestedMissingMovement")
  final case object NCTSToTraderSubmissionSuccessful    extends AuditType("NCTSToTraderSubmissionSuccessful")
  final case object ForwardedIncidentNotificationToED   extends AuditType("ForwardedIncidentNotificationToED")
  final private case object RequestOnNonArrivedMovement extends AuditType("RequestOnNonArrivedMovement")

  val values: Seq[AuditType] = Seq(
    AmendmentAcceptance,
    InvalidationDecision,
    Discrepancies,
    NotificationToAmendDeclaration,
    MRNAllocated,
    ReleaseForTransit,
    RecoveryNotification,
    WriteOffNotification,
    NoReleaseForTransit,
    UnloadingPermission,
    GuaranteeNotValid,
    RejectionFromOfficeOfDeparture,
    RejectionFromOfficeOfDestination,
    ControlDecisionNotification,
    GoodsReleaseNotification,
    RequestOnNonArrivedMovement,
    PositiveAcknowledge,
    NCTSRequestedMissingMovement,
    NCTSToTraderSubmissionSuccessful,
    ForwardedIncidentNotificationToED
  )

  def find(code: String): Option[AuditType] =
    values.find(_.name == code)

}
