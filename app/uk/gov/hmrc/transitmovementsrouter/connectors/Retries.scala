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

package uk.gov.hmrc.transitmovementsrouter.connectors

import cats.Applicative
import cats.implicits.toFunctorOps
import com.google.inject.ImplementedBy
import retry.PolicyDecision
import retry.PolicyDecision.DelayAndRetry
import retry.PolicyDecision.GiveUp
import retry.RetryPolicies
import retry.RetryPolicy
import retry.RetryStatus
import uk.gov.hmrc.transitmovementsrouter.config.RetryConfig

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration

@ImplementedBy(classOf[RetriesImpl])
trait Retries {

  def createRetryPolicy(config: RetryConfig)(implicit ec: ExecutionContext): RetryPolicy[Future]

}

class RetriesImpl {

  def createRetryPolicy(
    config: RetryConfig
  )(implicit ec: ExecutionContext): RetryPolicy[Future] =
    limitRetriesByTotalTime(
      config.timeout,
      RetryPolicies.limitRetries[Future](config.maxRetries) join RetryPolicies
        .constantDelay[Future](config.delay)
    )

  def limitRetriesByTotalTime[M[_]: Applicative](
    threshold: FiniteDuration,
    policy: RetryPolicy[M]
  ): RetryPolicy[M] = {

    val endTime = System.currentTimeMillis() + threshold.toMillis

    def decideNextRetry(status: RetryStatus): M[PolicyDecision] =
      policy.decideNextRetry(status).map {
        case r @ DelayAndRetry(delay) =>
          if (System.currentTimeMillis() + delay.toMillis > endTime) GiveUp else r
        case GiveUp => GiveUp
      }

    RetryPolicy.withShow[M](
      decideNextRetry,
      s"limitRetriesByTotalTime(threshold=$threshold, $policy)"
    )
  }

}
