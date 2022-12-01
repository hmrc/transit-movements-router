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
import play.api.Logging
import play.api.http.HeaderNames
import play.api.http.MimeTypes
import retry.RetryDetails
import retry.alleycats.instances._
import retry.retryingOnFailures
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.HttpResponse
import uk.gov.hmrc.http.StringContextOps
import uk.gov.hmrc.http.UpstreamErrorResponse
import uk.gov.hmrc.http.{HeaderNames => HMRCHeaderNames}
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.transitmovementsrouter.config.EISInstanceConfig
import uk.gov.hmrc.transitmovementsrouter.models.CorrelationId
import uk.gov.hmrc.transitmovementsrouter.models.MessageId
import uk.gov.hmrc.transitmovementsrouter.models.MessageSender
import uk.gov.hmrc.transitmovementsrouter.models.MovementId
import uk.gov.hmrc.transitmovementsrouter.services.error.RoutingError
import uk.gov.hmrc.http.HttpReads.Implicits._
import java.util.UUID
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Try
import scala.util.control.NonFatal

trait EISConnector {

  def post(messageSender: MessageSender, body: Source[ByteString, _], hc: HeaderCarrier): Future[Either[RoutingError, Unit]]

}

class EISConnectorImpl(
  val code: String,
  val eisInstanceConfig: EISInstanceConfig,
  headerCarrierConfig: HeaderCarrier.Config,
  httpClientV2: HttpClientV2,
  retries: Retries
)(implicit
  ec: ExecutionContext,
  val materializer: Materializer
) extends EISConnector
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

  def shouldCauseCircuitBreakerStrike(result: Try[Either[RoutingError, Unit]]): Boolean =
    result.map(_.isLeft).getOrElse(true)

  def onFailure(response: Either[RoutingError, Unit], retryDetails: RetryDetails): Future[Unit] =
    response.left.get match {
      case RoutingError.Upstream(upstreamErrorResponse) => attemptRetry(s"with status code ${upstreamErrorResponse.statusCode}", retryDetails)
      case RoutingError.Unexpected(message, _)          => attemptRetry(s"with error $message", retryDetails)
      case x: RoutingError =>
        logger.error(s"An unexpected error occurred - got a ${x.getClass}")
        Future.failed(new IllegalStateException(s"An unexpected error occurred - got a ${x.getClass}"))
    }

  override def post(messageSender: MessageSender, body: Source[ByteString, _], hc: HeaderCarrier): Future[Either[RoutingError, Unit]] =
    retryingOnFailures(
      retries.createRetryPolicy(eisInstanceConfig.retryConfig),
      (t: Either[RoutingError, Unit]) => Future.successful(t.isRight),
      onFailure
    ) {
      val data = messageSender.value.split("-")

      val correlationId = data match {
        case array if array.size == 2 => CorrelationId(MovementId(data(0)), MessageId(data(1))).value
        case _                        => UUID.randomUUID().toString
      }
      val requestHeaders = hc.headers(OutgoingHeaders.headers) ++ Seq(
        "X-Correlation-Id"        -> correlationId,
        "CustomProcessHost"       -> "Digital",
        "X-Message-Sender"        -> messageSender.value,
        "X-Conversation-Id"       -> UUID.randomUUID().toString,
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
                |X-Message-Type: ${getHeader("X-Message-Type", eisInstanceConfig.url)(headerCarrier)} // TODO - might need this from the service too
                |X-Message-Sender: ${getHeader("X-Message-Sender", eisInstanceConfig.url)(headerCarrier)}
                |Accept: ${getHeader("Accept", eisInstanceConfig.url)(headerCarrier)}
                |CustomProcessHost: ${getHeader("CustomProcessHost", eisInstanceConfig.url)(headerCarrier)}
                |""".stripMargin

      withCircuitBreaker[Either[RoutingError, Unit]](shouldCauseCircuitBreakerStrike) {

        httpClientV2
          .post(url"${eisInstanceConfig.url}")
          .withBody(body)
          .addHeaders(headerCarrier.headersForUrl(headerCarrierConfig)(eisInstanceConfig.url): _*)
          .execute[Either[UpstreamErrorResponse, HttpResponse]]
          .map {
            case Right(result) =>
              logger.info(logMessage + s"Response status: ${result.status}")
              Right(())
            case Left(error) =>
              logger.warn(logMessage + s"Response status: ${error.statusCode}")
              Left(RoutingError.Upstream(UpstreamErrorResponse(s"Request Error: Routing to $code returned status code ${error.statusCode}", error.statusCode)))
          }
          .recover {
            case NonFatal(e) =>
              val message = logMessage + s"Request Error: Routing to $code failed to retrieve data with message ${e.getMessage}"
              logger.error(message)
              Left(RoutingError.Unexpected(message, Some(e)))
          }
      }
    }

  private def attemptRetry(message: String, retryDetails: RetryDetails): Future[Unit] = {
    val attemptNumber = retryDetails.retriesSoFar + 1
    if (retryDetails.givingUp) {
      logger.error(
        s"Message when routing to $code failed $message\n" +
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
        s"Message when routing to $code failed with $message\n" +
          s"Attempted $attemptNumber times in ${retryDetails.cumulativeDelay.toSeconds} seconds so far, trying again $nextAttempt."
      )
    }
    Future.unit
  }
}
