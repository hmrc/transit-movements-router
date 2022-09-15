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

package uk.gov.hmrc.transitmovementsrouter.controllers.actions

import com.google.inject.ImplementedBy
import com.google.inject.Inject
import play.api.mvc.Request
import uk.gov.hmrc.transitmovementsrouter.config.AppConfig

import scala.concurrent.ExecutionContext
import scala.language.higherKinds

@ImplementedBy(classOf[MessageSizeActionProviderImpl])
trait MessageSizeActionProvider {

  def apply[R[_] <: Request[_]](): MessageSizeAction[R]

}

class MessageSizeActionProviderImpl @Inject() (appConfig: AppConfig)(implicit ec: ExecutionContext) extends MessageSizeActionProvider {

  def apply[R[_] <: Request[_]](): MessageSizeAction[R] = new MessageSizeActionImpl[R](appConfig)

}
