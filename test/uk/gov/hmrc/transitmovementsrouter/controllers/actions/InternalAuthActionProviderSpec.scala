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

import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.{eq => eqTo}
import org.mockito.MockitoSugar
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import play.api.mvc.ActionBuilder
import play.api.mvc.AnyContent
import play.api.mvc.DefaultActionBuilder
import play.api.test.Helpers.stubControllerComponents
import uk.gov.hmrc.internalauth.client.AuthenticatedRequest
import uk.gov.hmrc.internalauth.client.BackendAuthComponents
import uk.gov.hmrc.internalauth.client.IAAction
import uk.gov.hmrc.internalauth.client.Predicate
import uk.gov.hmrc.internalauth.client.Resource
import uk.gov.hmrc.internalauth.client.ResourceLocation
import uk.gov.hmrc.internalauth.client.ResourceType
import uk.gov.hmrc.internalauth.client.Retrieval.EmptyRetrieval
import uk.gov.hmrc.transitmovementsrouter.config.AppConfig

import scala.concurrent.ExecutionContext.Implicits.global

class InternalAuthActionProviderSpec extends AnyFreeSpec with Matchers with MockitoSugar {

  type AuthReq[R] = AuthenticatedRequest[R, Unit]

  "InternalAuthActionProvider#apply" - {

    "when internal auth is disabled" - {

      "return action should not be the auth action" in {

        val appConfig = mock[AppConfig]
        when(appConfig.internalAuthEnabled).thenReturn(false)

        val mockActionBuilder         = mock[ActionBuilder[AuthReq, AnyContent]]
        val mockBackendAuthComponents = mock[BackendAuthComponents]
        when(mockBackendAuthComponents.authorizedAction(any[Predicate], eqTo(EmptyRetrieval), any(), any())).thenReturn(mockActionBuilder)

        val samplePermission = Predicate.Permission(Resource(ResourceType("transit-movements-auditing"), ResourceLocation("audit")), IAAction("WRITE"))
        val sut              = new InternalAuthActionProviderImpl(appConfig, mockBackendAuthComponents, stubControllerComponents())

        sut(samplePermission) mustBe a[DefaultActionBuilder]
      }

    }

    "when internal auth is enabled" - {

      "return action should not be the auth action" in {

        val appConfig = mock[AppConfig]
        when(appConfig.internalAuthEnabled).thenReturn(true)

        val mockActionBuilder         = mock[ActionBuilder[AuthReq, AnyContent]]
        val mockBackendAuthComponents = mock[BackendAuthComponents]
        when(mockBackendAuthComponents.authorizedAction(any[Predicate], eqTo(EmptyRetrieval), any(), any())).thenReturn(mockActionBuilder)

        val samplePermission = Predicate.Permission(Resource(ResourceType("transit-movements-auditing"), ResourceLocation("audit")), IAAction("WRITE"))
        val sut              = new InternalAuthActionProviderImpl(appConfig, mockBackendAuthComponents, stubControllerComponents())

        sut(samplePermission) mustBe mockActionBuilder
      }

    }
  }

}
