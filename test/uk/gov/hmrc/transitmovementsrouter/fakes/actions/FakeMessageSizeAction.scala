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

package uk.gov.hmrc.transitmovementsrouter.fakes.actions

import play.api.mvc.Request
import play.api.mvc.Result
import uk.gov.hmrc.transitmovementsrouter.controllers.actions.MessageSizeAction

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

class FakeMessageSizeAction[R[_] <: Request[_]] extends MessageSizeAction[R] {
  override protected def filter[A](request: R[A]): Future[Option[Result]] = Future.successful(None)

  override protected def executionContext: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global
}
