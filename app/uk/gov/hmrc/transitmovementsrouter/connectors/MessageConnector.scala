package uk.gov.hmrc.transitmovementsrouter.connectors

import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.google.inject.ImplementedBy
import com.google.inject.Inject
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
import uk.gov.hmrc.transitmovementsrouter.config.AppConfig
import uk.gov.hmrc.transitmovementsrouter.config.EISInstanceConfig
import uk.gov.hmrc.transitmovementsrouter.models.MessageSender
import uk.gov.hmrc.transitmovementsrouter.models.RoutingOption
import uk.gov.hmrc.transitmovementsrouter.services.RetriesService

import java.util.UUID
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Try
import scala.util.control.NonFatal

@ImplementedBy(classOf[MessageConnectorImpl])
trait MessageConnector {

  def post(routingOption: RoutingOption, messageSender: MessageSender, body: Source[ByteString, _])(implicit hc: HeaderCarrier): Future[Either[UpstreamErrorResponse, Unit]]

}

// TODO: Change WSClient for HttpClient2 when https://github.com/hmrc/bootstrap-play/pull/75 is pulled
class MessageConnectorImpl @Inject()(val appConfig: AppConfig, retriesService: RetriesService, ws: WSClient)
                                    (implicit ec: ExecutionContext, val materializer: Materializer)
  extends MessageConnector with Logging with CircuitBreakers {

  def getHeader(header: String, url: String)(implicit hc: HeaderCarrier): String =
    hc
      .headersForUrl(appConfig.headerCarrierConfig)(url)
      .find { case (name, _) => name.toLowerCase == header.toLowerCase }
      .map { case (_, value) => value }
      .getOrElse("undefined")

  private def statusCodeFailure(status: Int): Boolean =
    shouldCauseRetry(status) || status == Status.FORBIDDEN

  private def shouldCauseRetry(status: Int): Boolean =
    Status.isServerError(status)

  def shouldCauseCircuitBreakerStrike(result: Try[Either[UpstreamErrorResponse, Unit]]): Boolean =
    result.map(_.isLeft).getOrElse(true)

  def withEISInstance[T](routingOption: RoutingOption)(block: EISInstanceConfig => Future[T]): Future[T] =
    block(routingOption.config(appConfig))

  def onFailure(
     instance: String
   )(response: Either[UpstreamErrorResponse, Unit], retryDetails: RetryDetails): Future[Unit] = {
    val statusCode = response.left.get.statusCode // we always have a left
    val attemptNumber = retryDetails.retriesSoFar + 1
    if (retryDetails.givingUp) {
      logger.error(
        s"Message when routing to $instance failed with status code $statusCode. " +
          s"Attempted $attemptNumber times in ${retryDetails.cumulativeDelay.toSeconds} seconds, giving up."
      )
    } else {
      val nextAttempt =
        retryDetails.upcomingDelay.map(d => s"in ${d.toSeconds} seconds").getOrElse("immediately")
      logger.warn(
        s"Message when routing to $instance failed with status code $statusCode. " +
          s"Attempted $attemptNumber times in ${retryDetails.cumulativeDelay.toSeconds} seconds so far, trying again $nextAttempt."
      )
    }
    Future.unit
  }

  override def post(routingOption: RoutingOption, messageSender: MessageSender, body: Source[ByteString, _])
                   (implicit hc: HeaderCarrier): Future[Either[UpstreamErrorResponse, Unit]] =
    withEISInstance(routingOption) {
      config =>
        retryingOnFailures(
          retriesService.createRetryPolicy(config.retryConfig),
          (t: Either[UpstreamErrorResponse, Unit]) => Future.successful(t.isRight),
          onFailure(routingOption.code)
        ) {
          val requestHeaders = hc.headers(OutgoingHeaders.headers) ++ Seq(
            "X-Correlation-Id" -> UUID.randomUUID().toString,
            "CustomProcessHost" -> "Digital",
            HeaderNames.ACCEPT -> MimeTypes.XML, // can't use ContentTypes.XML because EIS will not accept "application/xml; charset=utf-8"
            HeaderNames.AUTHORIZATION -> s"Bearer ${config.headers.bearerToken}"
          )

          implicit val headerCarrier: HeaderCarrier = hc
            .copy(authorization = None, otherHeaders = Seq.empty)
            .withExtraHeaders(requestHeaders: _*)

          val requestId = getHeader(HMRCHeaderNames.xRequestId, config.url)(headerCarrier)
          lazy val logMessage =
            s"""|Posting NCTS message, routing to ${routingOption.code}
                |X-Correlation-Id: ${getHeader("X-Correlation-Id", config.url)(headerCarrier)}
                |${HMRCHeaderNames.xRequestId}: $requestId
                |X-Message-Type: ${getHeader("X-Message-Type", config.url)(headerCarrier)}
                |X-Message-Sender: ${getHeader("X-Message-Sender", config.url)(headerCarrier)}
                |Accept: ${getHeader("Accept", config.url)(headerCarrier)}
                |CustomProcessHost: ${getHeader("CustomProcessHost", config.url)(headerCarrier)}
                |""".stripMargin

          withCircuitBreaker[Either[UpstreamErrorResponse, Unit]](routingOption, shouldCauseCircuitBreakerStrike) {
            ws.url(config.url)
              .addHttpHeaders(headerCarrier.headersForUrl(appConfig.headerCarrierConfig)(config.url): _*)
              .post(body)
              .map {
                result =>
                  val logMessageWithStatus = logMessage + s"Response status: ${result.status}"
                  if (statusCodeFailure(result.status)) {
                    logger.warn(logMessageWithStatus)
                    Left(UpstreamErrorResponse(s"Request Error: Routing to ${routingOption.code} returned status code ${result.status}", result.status))
                  } else {
                    logger.info(logMessageWithStatus)
                    Right(())
                  }
              }
              .recover {
                case NonFatal(e) =>
                  val message = logMessage + s"Request Error: Routing to ${routingOption.code} failed to retrieve data with message ${e.getMessage}"
                  logger.error(message)
                  Left(UpstreamErrorResponse(message = message, statusCode = Status.INTERNAL_SERVER_ERROR))
              }
          }
        }
    }
}