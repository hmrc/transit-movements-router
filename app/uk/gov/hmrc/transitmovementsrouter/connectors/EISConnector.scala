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
import play.api.http.Status.INTERNAL_SERVER_ERROR
import play.api.libs.ws.BodyWritable
import play.api.libs.ws.SourceBody
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
import uk.gov.hmrc.transitmovementsrouter.models.LocalReferenceNumber
import uk.gov.hmrc.transitmovementsrouter.models.MessageId
import uk.gov.hmrc.transitmovementsrouter.models.MovementId
import uk.gov.hmrc.transitmovementsrouter.models.errors.ErrorCode
import uk.gov.hmrc.transitmovementsrouter.models.errors.RoutingError
import uk.gov.hmrc.transitmovementsrouter.models.errors.RoutingError.DuplicateLRNError
import uk.gov.hmrc.transitmovementsrouter.utils.RouterHeaderNames

import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Success
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
  retries: Retries,
  clock: Clock,
  logBodyOn500: Boolean
)(implicit
  ec: ExecutionContext,
  val materializer: Materializer
) extends EISConnector
    with Logging
    with CircuitBreakers {

  private lazy val authorization = s"Bearer ${eisInstanceConfig.headers.bearerToken}"

  // Used when setting a stream body -- forces the correct content type (default chooses application/octet-stream)
  implicit private val xmlSourceWriter: BodyWritable[Source[ByteString, _]] = BodyWritable(SourceBody, MimeTypes.XML)

  private val HTTP_DATE_FORMATTER = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss", Locale.ENGLISH).withZone(ZoneOffset.UTC)

  private lazy val lrnRegexPattern = ".*The supplied LRN: ([a-zA-Z0-9]+) has already been used by submitter: ([a-zA-Z0-9]+).*".r

  private def nowFormatted(): String =
    s"${HTTP_DATE_FORMATTER.format(OffsetDateTime.now(clock.withZone(ZoneOffset.UTC)))} UTC"

  def shouldCauseCircuitBreakerStrike(result: Try[Either[RoutingError, Unit]]): Boolean =
    result match {
      case Success(Left(DuplicateLRNError(_, _, _))) => false // Not to open circuit breaker for DuplicateLRN error
      case Success(_)                                => true
      case _                                         => false
    }

  def onFailure(response: Either[RoutingError, Unit], retryDetails: RetryDetails): Future[Unit] =
    response.left.toOption.get match {
      case RoutingError.Upstream(upstreamErrorResponse) => attemptRetry(s"with status code ${upstreamErrorResponse.statusCode}", retryDetails)
      case RoutingError.Unexpected(message, _)          => attemptRetry(s"with error $message", retryDetails)
      case x: RoutingError =>
        logger.error(s"An unexpected error occurred - got a ${x.getClass}")
        Future.failed(new IllegalStateException(s"An unexpected error occurred - got a ${x.getClass}"))
    }

  override def post(movementId: MovementId, messageId: MessageId, body: Source[ByteString, _], hc: HeaderCarrier): Future[Either[RoutingError, Unit]] =
    retryingOnFailures(
      retries.createRetryPolicy(eisInstanceConfig.retryConfig),
      (t: Either[RoutingError, Unit]) =>
        t match {
          case Left(RoutingError.DuplicateLRNError(_, _, _)) => Future.successful(t.isLeft) //Not to want retry for DuplicateLRN error
          case _                                             => Future.successful(t.isRight)
        },
      onFailure
    ) {
      // blank-ish carrier so that we control what we're sending to EIS, and let the carrier/platform do the rest
      implicit val headerCarrier: HeaderCarrier =
        if (eisInstanceConfig.forwardClientId)
          HeaderCarrier(requestChain = hc.requestChain, extraHeaders = hc.headers(Seq("X-Client-Id")))
        else HeaderCarrier(requestChain = hc.requestChain)

      val requestId      = hc.requestId.map(_.value)
      val correlationId  = UUID.randomUUID().toString
      val accept         = MimeTypes.XML
      val conversationId = ConversationId(movementId, MessageId("0000000000000000")) // ConversationId(movementId, messageId)
      val date           = HTTP_DATE_FORMATTER.format(OffsetDateTime.now())

      val messageType =
        hc.headersForUrl(headerCarrierConfig)(eisInstanceConfig.url).find(_._1 equalsIgnoreCase RouterHeaderNames.MESSAGE_TYPE).map(_._2).getOrElse("undefined")
      lazy val logMessage =
        s"""|Posting NCTS message, routing to $code
            |${HMRCHeaderNames.xRequestId}: ${requestId.getOrElse("undefined")}
            |${RouterHeaderNames.CORRELATION_ID}: $correlationId
            |${RouterHeaderNames.CONVERSATION_ID}: ${conversationId.value.toString}
            |${HeaderNames.ACCEPT}: $accept
            |${HeaderNames.CONTENT_TYPE}: ${xmlSourceWriter.contentType}
            |${HeaderNames.DATE}: $date
            |${RouterHeaderNames.MESSAGE_TYPE} (not submitted to EIS): $messageType
            |""".stripMargin

      withCircuitBreaker[Either[RoutingError, Unit]](shouldCauseCircuitBreakerStrike) {
        httpClientV2
          .post(url"${eisInstanceConfig.url}")
          .withBody(body)
          .setHeader(
            HMRCHeaderNames.xRequestId       -> requestId.getOrElse(""),
            HeaderNames.AUTHORIZATION        -> authorization,
            RouterHeaderNames.CORRELATION_ID -> correlationId,
            HeaderNames.ACCEPT               -> MimeTypes.XML,
            RouterHeaderNames.CONVERSATION_ID -> ConversationId(
              movementId,
              MessageId("0000000000000000")
            ).value.toString, // ConversationId(movementId, messageId).value.toString,
            HeaderNames.DATE -> nowFormatted()
          )
          .execute[Either[UpstreamErrorResponse, HttpResponse]]
          .map {
            case Right(result) =>
              logger.info(logMessage + s"Response status: ${result.status}")
              Right(())
            case Left(error) =>
              val status =
                if (logBodyOn500 && error.statusCode == INTERNAL_SERVER_ERROR) {
                  s"""Response status: ${error.statusCode}"
                     |Message:
                     |
                     |${error.message}""".stripMargin
                } else s"Response status: ${error.statusCode}"

              logger.warn(logMessage + status)

              error.statusCode match {
                case ErrorCode.Forbidden.statusCode | ErrorCode.InternalServerError.statusCode =>
                  error.message match {
                    case lrnRegexPattern(lrn, _) =>
                      Left(
                        RoutingError.DuplicateLRNError(s"LRN $lrn has previously been used and cannot be reused", ErrorCode.Conflict, LocalReferenceNumber(lrn))
                      )
                    case _ =>
                      Left(
                        RoutingError.Upstream(
                          UpstreamErrorResponse(s"Request Error: Routing to $code returned status code ${error.statusCode}", error.statusCode)
                        )
                      )
                  }
                case _ =>
                  Left(
                    RoutingError.Upstream(UpstreamErrorResponse(s"Request Error: Routing to $code returned status code ${error.statusCode}", error.statusCode))
                  )
              }
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
