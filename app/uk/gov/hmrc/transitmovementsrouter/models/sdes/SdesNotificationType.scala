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

package uk.gov.hmrc.transitmovementsrouter.models.sdes

import play.api.libs.json.JsError
import play.api.libs.json.JsString
import play.api.libs.json.JsSuccess
import play.api.libs.json.Json
import play.api.libs.json.Reads
import play.api.libs.json.Writes

sealed trait SdesNotificationType

object SdesNotificationType {
  private case object FileReady     extends SdesNotificationType
  private case object FileReceived  extends SdesNotificationType
  case object FileProcessed         extends SdesNotificationType
  case object FileProcessingFailure extends SdesNotificationType

  val values: Seq[SdesNotificationType] = Seq(FileProcessed, FileProcessingFailure)

  implicit val writes: Writes[SdesNotificationType] = (sdesNotificationType: SdesNotificationType) => Json.toJson(sdesNotificationType.toString)

  implicit val reads: Reads[SdesNotificationType] = Reads {
    case JsString(x) if x.toLowerCase == "fileready"             => JsSuccess(FileReady)
    case JsString(x) if x.toLowerCase == "filereceived"          => JsSuccess(FileReceived)
    case JsString(x) if x.toLowerCase == "fileprocessed"         => JsSuccess(FileProcessed)
    case JsString(x) if x.toLowerCase == "fileprocessingfailure" => JsSuccess(FileProcessingFailure)
    case _                                                       => JsError("Invalid file status")
  }
}
