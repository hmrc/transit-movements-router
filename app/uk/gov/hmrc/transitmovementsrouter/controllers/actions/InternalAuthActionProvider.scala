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

package uk.gov.hmrc.transitmovementsrouter.controllers.actions

import com.google.inject.ImplementedBy
import com.google.inject.Inject
import com.google.inject.Singleton
import play.api.mvc.ActionBuilder
import play.api.mvc.AnyContent
import play.api.mvc.ControllerComponents
import play.api.mvc.DefaultActionBuilder
import play.api.mvc.Request
import uk.gov.hmrc.internalauth.client.BackendAuthComponents
import uk.gov.hmrc.internalauth.client.Predicate
import uk.gov.hmrc.transitmovementsrouter.config.AppConfig

import scala.concurrent.ExecutionContext

@ImplementedBy(classOf[InternalAuthActionProviderImpl])
trait InternalAuthActionProvider {
  def apply(predicate: Predicate)(implicit ec: ExecutionContext): ActionBuilder[Request, AnyContent]

}

@Singleton
class InternalAuthActionProviderImpl @Inject() (appConfig: AppConfig, backendAuth: BackendAuthComponents, cc: ControllerComponents)
    extends InternalAuthActionProvider {

  override def apply(predicate: Predicate)(implicit ec: ExecutionContext): ActionBuilder[Request, AnyContent] =
    if (appConfig.internalAuthEnabled) backendAuth.authorizedAction(predicate)
    else DefaultActionBuilder(cc.parsers.anyContent)

}
