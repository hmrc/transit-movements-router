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

import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.google.inject.ImplementedBy
import play.api.Logging
import play.api.http.HeaderNames
import play.api.http.MimeTypes
import play.api.http.Status
import play.api.libs.ws.WSClient
import retry.RetryDetails
import retry.alleycats.instances._
import retry.retryingOnFailures
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.UpstreamErrorResponse
import uk.gov.hmrc.http.{HeaderNames => HMRCHeaderNames}
import uk.gov.hmrc.transitmovementsrouter.config.EISInstanceConfig
import uk.gov.hmrc.transitmovementsrouter.models.MessageSender

import java.util.UUID
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Try
import scala.util.control.NonFatal

trait MessageConnector {

  def post(messageSender: MessageSender, body: Source[ByteString, _], hc: HeaderCarrier): Future[Either[UpstreamErrorResponse, Unit]]

}

// TODO: Change WSClient for HttpClientV2 when https://github.com/hmrc/bootstrap-play/pull/75 is pulled
class MessageConnectorImpl(
  val code: String,
  val eisInstanceConfig: EISInstanceConfig,
  headerCarrierConfig: HeaderCarrier.Config,
  ws: WSClient,
  retries: Retries
)(implicit
  ec: ExecutionContext,
  val materializer: Materializer
) extends MessageConnector
    with Logging
    with CircuitBreakers {

  def getHeader(header: String, url: String)(implicit hc: HeaderCarrier): String =
    hc
      .headersForUrl(headerCarrierConfig)(url)
      .find {
        case (name, _) => name.toLowerCase == header.toLowerCase
      }
      .map {
        case (_, value) => value
      }
      .getOrElse("undefined")

  private def statusCodeFailure(status: Int): Boolean =
    shouldCauseRetry(status) || status == Status.FORBIDDEN

  private def shouldCauseRetry(status: Int): Boolean =
    Status.isServerError(status)

  def shouldCauseCircuitBreakerStrike(result: Try[Either[UpstreamErrorResponse, Unit]]): Boolean =
    result.map(_.isLeft).getOrElse(true)

  def onFailure(response: Either[UpstreamErrorResponse, Unit], retryDetails: RetryDetails): Future[Unit] = {
    val statusCode    = response.left.get.statusCode // we always have a left
    val attemptNumber = retryDetails.retriesSoFar + 1
    if (retryDetails.givingUp) {
      logger.error(
        s"Message when routing to $code failed with status code $statusCode. " +
          s"Attempted $attemptNumber times in ${retryDetails.cumulativeDelay.toSeconds} seconds, giving up."
      )
    } else {
      val nextAttempt =
        retryDetails.upcomingDelay
          .map(
            d => s"in ${d.toSeconds} seconds"
          )
          .getOrElse("immediately")
      logger.warn(
        s"Message when routing to $code failed with status code $statusCode. " +
          s"Attempted $attemptNumber times in ${retryDetails.cumulativeDelay.toSeconds} seconds so far, trying again $nextAttempt."
      )
    }
    Future.unit
  }

  override def post(messageSender: MessageSender, body: Source[ByteString, _], hc: HeaderCarrier): Future[Either[UpstreamErrorResponse, Unit]] =
    retryingOnFailures(
      retries.createRetryPolicy(eisInstanceConfig.retryConfig),
      (t: Either[UpstreamErrorResponse, Unit]) => Future.successful(t.isRight),
      onFailure
    ) {
      val requestHeaders = hc.headers(OutgoingHeaders.headers) ++ Seq(
        "X-Correlation-Id"        -> UUID.randomUUID().toString,
        "CustomProcessHost"       -> "Digital",
        HeaderNames.ACCEPT        -> MimeTypes.XML, // TODO: is this still relevant? Can't use ContentTypes.XML because EIS will not accept "application/xml; charset=utf-8"
        HeaderNames.AUTHORIZATION -> s"Bearer ${eisInstanceConfig.headers.bearerToken}"
      )

      implicit val headerCarrier: HeaderCarrier = hc
        .copy(authorization = None, otherHeaders = Seq.empty)
        .withExtraHeaders(requestHeaders: _*)

      val requestId = getHeader(HMRCHeaderNames.xRequestId, eisInstanceConfig.url)(headerCarrier)
      lazy val logMessage =
        s"""|Posting NCTS message, routing to $code
                |X-Correlation-Id: ${getHeader("X-Correlation-Id", eisInstanceConfig.url)(headerCarrier)}
                |${HMRCHeaderNames.xRequestId}: $requestId
                |X-Message-Type: ${getHeader("X-Message-Type", eisInstanceConfig.url)(headerCarrier)}
                |X-Message-Sender: ${getHeader("X-Message-Sender", eisInstanceConfig.url)(headerCarrier)}
                |Accept: ${getHeader("Accept", eisInstanceConfig.url)(headerCarrier)}
                |CustomProcessHost: ${getHeader("CustomProcessHost", eisInstanceConfig.url)(headerCarrier)}
                |""".stripMargin

      withCircuitBreaker[Either[UpstreamErrorResponse, Unit]](shouldCauseCircuitBreakerStrike) {
        ws.url(eisInstanceConfig.url)
          .addHttpHeaders(headerCarrier.headersForUrl(headerCarrierConfig)(eisInstanceConfig.url): _*)
          .post(body)
          .map {
            result =>
              val logMessageWithStatus = logMessage + s"Response status: ${result.status}"
              if (statusCodeFailure(result.status)) {
                logger.warn(logMessageWithStatus)
                Left(UpstreamErrorResponse(s"Request Error: Routing to $code returned status code ${result.status}", result.status))
              } else {
                logger.info(logMessageWithStatus)
                Right(())
              }
          }
          .recover {
            case NonFatal(e) =>
              val message = logMessage + s"Request Error: Routing to $code failed to retrieve data with message ${e.getMessage}"
              logger.error(message)
              Left(UpstreamErrorResponse(message = message, statusCode = Status.INTERNAL_SERVER_ERROR))
          }
      }
    }
}
