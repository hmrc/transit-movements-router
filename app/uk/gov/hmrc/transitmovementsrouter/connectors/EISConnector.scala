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
import uk.gov.hmrc.http.HttpReads.Implicits._
import uk.gov.hmrc.http.HttpResponse
import uk.gov.hmrc.http.StringContextOps
import uk.gov.hmrc.http.UpstreamErrorResponse
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.{HeaderNames => HMRCHeaderNames}
import uk.gov.hmrc.transitmovementsrouter.config.EISInstanceConfig
import uk.gov.hmrc.transitmovementsrouter.models.ConversationId
import uk.gov.hmrc.transitmovementsrouter.models.MessageId
import uk.gov.hmrc.transitmovementsrouter.models.MovementId
import uk.gov.hmrc.transitmovementsrouter.services.error.RoutingError
import uk.gov.hmrc.transitmovementsrouter.utils.RouterHeaderNames

import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Try
import scala.util.control.NonFatal

trait EISConnector {

  def post(movementId: MovementId, messageId: MessageId, body: Source[ByteString, _], hc: HeaderCarrier): Future[Either[RoutingError, Unit]]

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

  private val HTTP_DATE_FORMATTER = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss z", Locale.ENGLISH).withZone(ZoneOffset.UTC)

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
    response.left.toOption.get match {
      case RoutingError.Upstream(upstreamErrorResponse) => attemptRetry(s"with status code ${upstreamErrorResponse.statusCode}", retryDetails)
      case RoutingError.Unexpected(message, _)          => attemptRetry(s"with error $message", retryDetails)
      case x: RoutingError =>
        logger.error(s"An unexpected error occurred - got a ${x.getClass}")
        Future.failed(new IllegalStateException(s"An unexpected error occurred - got a ${x.getClass}"))
    }

  private def extractHeaders(movementId: MovementId, messageId: MessageId, hc: HeaderCarrier): Seq[(String, String)] = {
    val dateHeader: Seq[(String, String)] = hc.headers(Seq(HeaderNames.DATE)) match {
      case Seq() => Seq(HeaderNames.DATE -> HTTP_DATE_FORMATTER.format(OffsetDateTime.now()))
      case x     => x
    }

    val requestHeaders: Seq[(String, String)] = hc.headers(Seq(RouterHeaderNames.MESSAGE_TYPE)) ++ dateHeader ++ Seq(
      RouterHeaderNames.CORRELATION_ID      -> UUID.randomUUID().toString,
      RouterHeaderNames.CUSTOM_PROCESS_HOST -> "Digital",
      HeaderNames.CONTENT_TYPE              -> MimeTypes.XML,
      HeaderNames.ACCEPT                    -> MimeTypes.XML,
      HeaderNames.AUTHORIZATION             -> s"Bearer ${eisInstanceConfig.headers.bearerToken}",
      RouterHeaderNames.CONVERSATION_ID     -> ConversationId(movementId, messageId).value.toString
    )

    (hc
      .headersForUrl(headerCarrierConfig)(eisInstanceConfig.url))
      .filter(
        x =>
          !requestHeaders.exists(
            y => y._1 == x
          )
      ) ++ requestHeaders
  }

  override def post(movementId: MovementId, messageId: MessageId, body: Source[ByteString, _], hc: HeaderCarrier): Future[Either[RoutingError, Unit]] =
    retryingOnFailures(
      retries.createRetryPolicy(eisInstanceConfig.retryConfig),
      (t: Either[RoutingError, Unit]) => Future.successful(t.isRight),
      onFailure
    ) {

      val updatedHeader = hc.copy(authorization = None, extraHeaders = Seq.empty, otherHeaders = Seq.empty)

      implicit val headerCarrier: HeaderCarrier = updatedHeader
        .withExtraHeaders(extractHeaders(movementId, messageId, updatedHeader): _*)

      val requestId = getHeader(HMRCHeaderNames.xRequestId, eisInstanceConfig.url)(headerCarrier)
      lazy val logMessage =
        s"""|Posting NCTS message, routing to $code
                |${RouterHeaderNames.CORRELATION_ID}: ${getHeader(RouterHeaderNames.CORRELATION_ID, eisInstanceConfig.url)(headerCarrier)}
                |${HMRCHeaderNames.xRequestId}: $requestId
                |${RouterHeaderNames.MESSAGE_TYPE}: ${getHeader(RouterHeaderNames.MESSAGE_TYPE, eisInstanceConfig.url)(headerCarrier)}
                |${RouterHeaderNames.CONVERSATION_ID}: ${getHeader(RouterHeaderNames.CONVERSATION_ID, eisInstanceConfig.url)(headerCarrier)}
                |${HeaderNames.ACCEPT}: ${getHeader(HeaderNames.ACCEPT, eisInstanceConfig.url)(headerCarrier)}
                |${HeaderNames.CONTENT_TYPE}: ${getHeader(HeaderNames.CONTENT_TYPE, eisInstanceConfig.url)(headerCarrier)}
                |${RouterHeaderNames.CUSTOM_PROCESS_HOST}: ${getHeader(RouterHeaderNames.CUSTOM_PROCESS_HOST, eisInstanceConfig.url)(headerCarrier)}
                |""".stripMargin

      withCircuitBreaker[Either[RoutingError, Unit]](shouldCauseCircuitBreakerStrike) {
        httpClientV2
          .post(url"${eisInstanceConfig.url}")
          .withBody(body)
          .transform(_.addHttpHeaders(headerCarrier.headersForUrl(headerCarrierConfig)(eisInstanceConfig.url): _*))
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
