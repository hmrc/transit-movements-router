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

package test.uk.gov.hmrc.transitmovementsrouter.controllers

import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.matching.EqualToPattern
import com.github.tomakehurst.wiremock.matching.UrlPathPattern
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchersSugar.eqTo
import org.mockito.Mockito.when
import org.mockito.MockitoSugar.mock
import org.scalacheck.Gen
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import play.api.http.HeaderNames
import play.api.http.MimeTypes
import play.api.http.Status._
import play.api.inject.bind
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.inject.guice.GuiceableModule
import play.api.libs.json.Json
import play.api.test.FakeHeaders
import play.api.test.FakeRequest
import play.api.test.Helpers
import play.api.test.Helpers.defaultAwaitTimeout
import play.api.test.Helpers.running
import test.uk.gov.hmrc.transitmovementsrouter.it.base.RegexPatterns
import test.uk.gov.hmrc.transitmovementsrouter.it.base.TestMetrics
import test.uk.gov.hmrc.transitmovementsrouter.it.base.WiremockSuiteWithGuice
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.objectstore.client.Md5Hash
import uk.gov.hmrc.objectstore.client.ObjectSummaryWithMd5
import uk.gov.hmrc.objectstore.client.Path
import uk.gov.hmrc.objectstore.client.RetentionPeriod
import uk.gov.hmrc.objectstore.client.play.PlayObjectStoreClientEither
import uk.gov.hmrc.play.bootstrap.metrics.Metrics
import uk.gov.hmrc.transitmovementsrouter.config.Constants
import uk.gov.hmrc.transitmovementsrouter.controllers.MessagesController
import uk.gov.hmrc.transitmovementsrouter.models.ConversationId
import uk.gov.hmrc.transitmovementsrouter.models.EoriNumber
import uk.gov.hmrc.transitmovementsrouter.models.MovementType
import uk.gov.hmrc.transitmovementsrouter.utils.UUIDGenerator

import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import scala.concurrent.Future

class MessagesControllerIntegrationSpec
    extends AnyFreeSpec
    with GuiceOneAppPerSuite
    with Matchers
    with WiremockSuiteWithGuice
    with ScalaCheckDrivenPropertyChecks {

  // We don't care about the content in this XML fragment, only the root tag and its child.
  val sampleIncomingXml: String =
    <n1:TraderChannelResponse xmlns:txd="http://ncts.dgtaxud.ec" xmlns:n1="http://www.hmrc.gov.uk/eis/ncts5/v1" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://www.hmrc.gov.uk/eis/ncts5/v1 EIS_WrapperV11_TraderChannelResponse-51.8.xsd">
      <txd:CC004C PhaseID="NCTS5.0">
        <messageSender>!</messageSender>
        <messageRecipient>!</messageRecipient>
        <preparationDateAndTime>0231-11-23T10:03:02</preparationDateAndTime>
        <messageIdentification>!</messageIdentification>
        <messageType>CC004C</messageType>
        <correlationIdentifier>!</correlationIdentifier>
        <TransitOperation>
          <LRN></LRN>
          <MRN>00AA00000000000000</MRN>
          <amendmentSubmissionDateAndTime>0231-11-23T10:03:02</amendmentSubmissionDateAndTime>
          <amendmentAcceptanceDateAndTime>0231-11-23T10:03:02</amendmentAcceptanceDateAndTime>
        </TransitOperation>
        <CustomsOfficeOfDeparture>
          <referenceNumber>AA000000</referenceNumber>
        </CustomsOfficeOfDeparture>
        <HolderOfTheTransitProcedure>
          <identificationNumber></identificationNumber>
          <TIRHolderIdentificationNumber></TIRHolderIdentificationNumber>
          <name></name>
          <Address>
            <streetAndNumber></streetAndNumber>
            <postcode></postcode>
            <city></city>
            <country>AA</country>
          </Address>
        </HolderOfTheTransitProcedure>
      </txd:CC004C>
    </n1:TraderChannelResponse>.mkString

  // We don't care about the content in this XML fragment, only the root tag and its child.
  val sampleOutgoingXml: String =
    <ncts:CC015C PhaseID="NCTS5.0" xmlns:ncts="http://ncts.dgtaxud.ec">
      <preparationDateAndTime>2022-05-25T09:37:04</preparationDateAndTime>
      <CustomsOfficeOfDeparture>
        <referenceNumber>GB1234567</referenceNumber>
      </CustomsOfficeOfDeparture>
    </ncts:CC015C>.mkString

  val sampleOutgoingXmlWrapped: String =
    <n1:TraderChannelSubmission xmlns:txd="http://ncts.dgtaxud.ec" xmlns:n1="http://www.hmrc.gov.uk/eis/ncts5/v1" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://www.hmrc.gov.uk/eis/ncts5/v1 EIS_WrapperV10_TraderChannelSubmission-51.8.xsd">
      <txd:CC015C PhaseID="NCTS5.0">
        <preparationDateAndTime>2022-05-25T09:37:04</preparationDateAndTime>
        <CustomsOfficeOfDeparture>
          <referenceNumber>GB1234567</referenceNumber>
        </CustomsOfficeOfDeparture>
      </txd:CC015C>
    </n1:TraderChannelSubmission>.mkString

  val sampleOutgoingLargeXml: String =
    <ncts:CC015C PhaseID="NCTS5.0" xmlns:ncts="http://ncts.dgtaxud.ec">
      <preparationDateAndTime>2022-05-25T09:37:04</preparationDateAndTime>
      <CustomsOfficeOfDeparture>
        <referenceNumber>GB1234567</referenceNumber>
      </CustomsOfficeOfDeparture>
      <something>
       <somethingElse>test</somethingElse>
      </something>
    </ncts:CC015C>.mkString

  val sampleOutgoingLargeXmlWrapped: String =
    <n1:TraderChannelRequest xmlns:txd="http://ncts.dgtaxud.ec" xmlns:n1="http://www.hmrc.gov.uk/eis/ncts5/v1" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://www.hmrc.gov.uk/eis/ncts5/v1 EIS_WrapperV11_TraderChannelRequest-51.8.xsd">
      <txd:CC015C PhaseID="NCTS5.0" xmlns:ncts="http://ncts.dgtaxud.ec">
        <preparationDateAndTime>2022-05-25T09:37:04</preparationDateAndTime>
        <CustomsOfficeOfDeparture>
          <referenceNumber>GB1234567</referenceNumber>
        </CustomsOfficeOfDeparture>
        <something>
          <somethingElse>test</somethingElse>
        </something>
      </txd:CC015C>
    </n1:TraderChannelRequest>.mkString

  val sampleOutgoingLargeXmlSize: Long = ByteString(sampleOutgoingLargeXml).size - 1

  val sampleOutgoingXIXml: String =
    <ncts:CC015C PhaseID="NCTS5.0" xmlns:ncts="http://ncts.dgtaxud.ec">
      <preparationDateAndTime>2022-05-25T09:37:04</preparationDateAndTime>
      <CustomsOfficeOfDeparture>
        <referenceNumber>XI1234567</referenceNumber>
      </CustomsOfficeOfDeparture>
    </ncts:CC015C>.mkString

  val sampleOutgoingXIXmlWrapped: String =
    <n1:TraderChannelSubmission xmlns:txd="http://ncts.dgtaxud.ec" xmlns:n1="http://www.hmrc.gov.uk/eis/ncts5/v1" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://www.hmrc.gov.uk/eis/ncts5/v1 EIS_WrapperV10_TraderChannelSubmission-51.8.xsd">
      <txd:CC015C PhaseID="NCTS5.0">
        <preparationDateAndTime>2022-05-25T09:37:04</preparationDateAndTime>
        <CustomsOfficeOfDeparture>
          <referenceNumber>XI1234567</referenceNumber>
        </CustomsOfficeOfDeparture>
      </txd:CC015C>
    </n1:TraderChannelSubmission>.mkString

  val brokenXml: String =
    """<nope>
      <ncts:CC029C xmlns:ncts="http://ncts.dgtaxud.ec" PhaseID="NCTS5.0">
        <preparationDateAndTime>2022-05-25T09:37:04</preparationDateAndTime>
        <CustomsOfficeOfDeparture>
          <referenceNumber>GB1234567</referenceNumber>
        </CustomsOfficeOfDeparture>
      </ncts:CC029C>
    </nope>"""

  override protected def appBuilder: GuiceApplicationBuilder =
    super.appBuilder.configure(
      "incomingRequestAuth.enabled"                                     -> true,
      "incomingRequestAuth.acceptedTokens.0"                            -> "ABC",
      "incomingRequestAuth.acceptedTokens.1"                            -> "123",
      "microservice.services.transit-movements.port"                    -> server.port().toString,
      "microservice.services.transit-movements-push-notifications.port" -> server.port().toString,
      "microservice.services.eis.gb.uri"                                -> "/gb",
      "microservice.services.eis.xi.uri"                                -> "/xi",
      "microservice.services.eis.gb.retry.max-retries"                  -> 0,
      "microservice.services.eis.xi.retry.max-retries"                  -> 0,
      "microservice.services.eis.gb_v2_1.uri"                           -> "/gb_v2_1",
      "microservice.services.eis.xi_v2_1.uri"                           -> "/xi_v2_1",
      "microservice.services.eis.gb_v2_1.retry.max-retries"             -> 0,
      "microservice.services.eis.xi_v2_1.retry.max-retries"             -> 0,
      "microservice.services.eis.message-size-limit"                    -> s"${sampleOutgoingLargeXmlSize}B",
      "microservice.services.internal-auth.enabled"                     -> false,
      "metrics.jvm"                                                     -> false
    )

  private val clock   = Clock.fixed(OffsetDateTime.of(2023, 4, 13, 10, 34, 41, 500, ZoneOffset.UTC).toInstant, ZoneOffset.UTC)
  private val md5hash = "d41d8cd98f00b204e9800998ecf8427e"

  private val objectStoreService = mock[PlayObjectStoreClientEither]
  when(
    objectStoreService.putObject(
      any[Path.File],
      any[Source[ByteString, _]],
      eqTo(RetentionPeriod.OneWeek),
      eqTo(Some(MimeTypes.XML)),
      eqTo(None),
      eqTo("transit-movements-router")
    )(any(), any[HeaderCarrier])
  )
    .thenAnswer(
      invoc =>
        Future.successful(
          Right(
            ObjectSummaryWithMd5(
              invoc.getArgument[Path.File](0),
              1000,
              Md5Hash("1B2M2Y8AsgTpgAmY7PhCfg=="), // base 64, rather than hex
              Instant.now()
            )
          )
        )
    )

  object SingleUUIDGenerator extends UUIDGenerator {
    lazy val uuid: UUID               = UUID.randomUUID()
    override def generateUUID(): UUID = uuid
  }

  override protected lazy val bindings: Seq[GuiceableModule] = Seq(
    bind[Metrics].to[TestMetrics],
    bind[Clock].toInstance(clock),
    bind[PlayObjectStoreClientEither].toInstance(objectStoreService),
    bind[UUIDGenerator].toInstance(SingleUUIDGenerator)
  )

  "outgoing" - {
    "small messages" - {

      "with a small valid body routing to GB, return 201" in {
        // We do this instead of using the standard "app" because we otherwise get the error
        // "Trying to materialize stream after materializer has been shutdown".
        // We suspect it's due to nested tests.
        val newApp                  = appBuilder.build()
        val conversationId          = ConversationId(UUID.randomUUID())
        val (movementId, messageId) = conversationId.toMovementAndMessageId

        server.stubFor(
          post(
            urlEqualTo("/gb")
          )
            .withHeader(HeaderNames.AUTHORIZATION, equalTo(s"Bearer bearertoken"))
            .withHeader("Date", equalTo("Thu, 13 Apr 2023 10:34:41 UTC"))
            .withHeader("X-Correlation-Id", matching(RegexPatterns.UUID))
            .withHeader("X-Conversation-Id", equalTo(conversationId.value.toString))
            .withHeader(HeaderNames.ACCEPT, equalTo("application/xml"))
            .withHeader(HeaderNames.CONTENT_TYPE, equalTo("application/xml"))
            .withRequestBody(equalToXml(sampleOutgoingXmlWrapped))
            .willReturn(aResponse().withStatus(OK))
        )

        val apiRequest = FakeRequest(
          "POST",
          s"/traders/GB0123456789/movements/departures/${movementId.value}/messages/${messageId.value}",
          FakeHeaders(
            Seq(
              "x-message-type"         -> "IE015",
              "x-request-id"           -> UUID.randomUUID().toString,
              HeaderNames.CONTENT_TYPE -> MimeTypes.XML
            )
          ),
          Source.single(ByteString(sampleOutgoingXml))
        )

        running(newApp) {
          val sut    = newApp.injector.instanceOf[MessagesController]
          val result = sut.outgoing(EoriNumber("GB0123456789"), MovementType("departures"), movementId, messageId)(apiRequest)

          Helpers.status(result) mustBe CREATED
        }
      }

      "with a small valid body routing to GB_V2_1, return 201" in {
        // We do this instead of using the standard "app" because we otherwise get the error
        // "Trying to materialize stream after materializer has been shutdown".
        // We suspect it's due to nested tests.
        val newApp                  = appBuilder.build()
        val conversationId          = ConversationId(UUID.randomUUID())
        val (movementId, messageId) = conversationId.toMovementAndMessageId

        server.stubFor(
          post(
            urlEqualTo("/gb_v2_1")
          )
            .withHeader(HeaderNames.AUTHORIZATION, equalTo(s"Bearer bearertoken"))
            .withHeader("Date", equalTo("Thu, 13 Apr 2023 10:34:41 UTC"))
            .withHeader("X-Correlation-Id", matching(RegexPatterns.UUID))
            .withHeader("X-Conversation-Id", equalTo(conversationId.value.toString))
            .withHeader(HeaderNames.ACCEPT, equalTo("application/xml"))
            .withHeader(HeaderNames.CONTENT_TYPE, equalTo("application/xml"))
            .withRequestBody(equalToXml(sampleOutgoingXmlWrapped))
            .willReturn(aResponse().withStatus(OK))
        )

        val apiRequest = FakeRequest(
          "POST",
          s"/traders/GB0123456789/movements/departures/${movementId.value}/messages/${messageId.value}",
          FakeHeaders(
            Seq(
              "x-message-type"              -> "IE015",
              "x-request-id"                -> UUID.randomUUID().toString,
              HeaderNames.CONTENT_TYPE      -> MimeTypes.XML,
              Constants.APIVersionHeaderKey -> Constants.APIVersionFinalHeaderValue
            )
          ),
          Source.single(ByteString(sampleOutgoingXml))
        )

        running(newApp) {
          val sut    = newApp.injector.instanceOf[MessagesController]
          val result = sut.outgoing(EoriNumber("GB0123456789"), MovementType("departures"), movementId, messageId)(apiRequest)

          Helpers.status(result) mustBe CREATED
        }
      }

      "with a small valid body routing to XI_V2_1, return 202" in {
        val newApp                  = appBuilder.build()
        val conversationId          = ConversationId(UUID.randomUUID())
        val (movementId, messageId) = conversationId.toMovementAndMessageId

        val time      = OffsetDateTime.of(2023, 2, 14, 15, 55, 28, 0, ZoneOffset.UTC)
        val formatted = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss z", Locale.ENGLISH).withZone(ZoneOffset.UTC).format(time)

        server.stubFor(
          post(
            urlEqualTo("/xi_v2_1")
          )
            .withHeader(HeaderNames.AUTHORIZATION, equalTo(s"Bearer bearertoken"))
            .withHeader("Date", equalTo("Thu, 13 Apr 2023 10:34:41 UTC"))
            .withHeader("X-Correlation-Id", matching(RegexPatterns.UUID))
            .withHeader("X-Conversation-Id", equalTo(conversationId.value.toString))
            .withHeader(HeaderNames.ACCEPT, equalTo("application/xml"))
            .withHeader(HeaderNames.CONTENT_TYPE, equalTo("application/xml"))
            .withRequestBody(equalToXml(sampleOutgoingXIXmlWrapped))
            .willReturn(aResponse().withStatus(OK))
        )

        val apiRequest = FakeRequest(
          "POST",
          s"/traders/GB0123456789/movements/departures/${movementId.value}/messages/${messageId.value}",
          FakeHeaders(
            Seq(
              "Date"                        -> formatted,
              "x-message-type"              -> "IE015",
              "x-request-id"                -> UUID.randomUUID().toString,
              HeaderNames.CONTENT_TYPE      -> MimeTypes.XML,
              Constants.APIVersionHeaderKey -> Constants.APIVersionFinalHeaderValue
            )
          ),
          Source.single(ByteString(sampleOutgoingXIXml))
        )

        running(newApp) {
          val sut    = newApp.injector.instanceOf[MessagesController]
          val result = sut.outgoing(EoriNumber("GB0123456789"), MovementType("departures"), movementId, messageId)(apiRequest)

          Helpers.status(result) mustBe CREATED
        }
      }

      "with a small valid body routing to XI, return 202" in {
        // We do this instead of using the standard "app" because we otherwise get the error
        // "Trying to materialize stream after materializer has been shutdown".
        // We suspect it's due to nested tests.
        val newApp                  = appBuilder.build()
        val conversationId          = ConversationId(UUID.randomUUID())
        val (movementId, messageId) = conversationId.toMovementAndMessageId

        val time      = OffsetDateTime.of(2023, 2, 14, 15, 55, 28, 0, ZoneOffset.UTC)
        val formatted = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss z", Locale.ENGLISH).withZone(ZoneOffset.UTC).format(time)

        server.stubFor(
          post(
            urlEqualTo("/xi")
          )
            .withHeader(HeaderNames.AUTHORIZATION, equalTo(s"Bearer bearertoken"))
            .withHeader("Date", equalTo("Thu, 13 Apr 2023 10:34:41 UTC"))
            .withHeader("X-Correlation-Id", matching(RegexPatterns.UUID))
            .withHeader("X-Conversation-Id", equalTo(conversationId.value.toString))
            .withHeader(HeaderNames.ACCEPT, equalTo("application/xml"))
            .withHeader(HeaderNames.CONTENT_TYPE, equalTo("application/xml"))
            .withRequestBody(equalToXml(sampleOutgoingXIXmlWrapped))
            .willReturn(aResponse().withStatus(OK))
        )

        val apiRequest = FakeRequest(
          "POST",
          s"/traders/GB0123456789/movements/departures/${movementId.value}/messages/${messageId.value}",
          FakeHeaders(
            Seq(
              "Date"                   -> formatted,
              "x-message-type"         -> "IE015",
              "x-request-id"           -> UUID.randomUUID().toString,
              HeaderNames.CONTENT_TYPE -> MimeTypes.XML
            )
          ),
          Source.single(ByteString(sampleOutgoingXIXml))
        )

        running(newApp) {
          val sut    = newApp.injector.instanceOf[MessagesController]
          val result = sut.outgoing(EoriNumber("GB0123456789"), MovementType("departures"), movementId, messageId)(apiRequest)

          Helpers.status(result) mustBe CREATED
        }
      }

      "with an incorrectly set bearer token routing to GB, return 500" in {
        // We do this instead of using the standard "app" because we otherwise get the error
        // "Trying to materialize stream after materializer has been shutdown".
        // We suspect it's due to nested tests.
        val newApp                  = appBuilder.build()
        val conversationId          = ConversationId(UUID.randomUUID())
        val (movementId, messageId) = conversationId.toMovementAndMessageId

        server.stubFor(
          post(
            urlEqualTo("/gb")
          )
            .withHeader(HeaderNames.AUTHORIZATION, equalTo(s"Bearer bearertoken"))
            .withHeader("Date", equalTo("Thu, 13 Apr 2023 10:34:41 UTC"))
            .withHeader("X-Correlation-Id", matching(RegexPatterns.UUID))
            .withHeader("X-Conversation-Id", equalTo(conversationId.value.toString))
            .withHeader(HeaderNames.ACCEPT, equalTo("application/xml"))
            .withHeader(HeaderNames.CONTENT_TYPE, equalTo("application/xml"))
            .withRequestBody(equalToXml(sampleOutgoingXmlWrapped))
            .willReturn(aResponse().withStatus(FORBIDDEN))
        )

        val apiRequest = FakeRequest(
          "POST",
          s"/traders/GB0123456789/movements/departures/${movementId.value}/messages/${messageId.value}",
          FakeHeaders(
            Seq(
              "x-message-type"         -> "IE015",
              "x-request-id"           -> UUID.randomUUID().toString,
              HeaderNames.CONTENT_TYPE -> MimeTypes.XML
            )
          ),
          Source.single(ByteString(sampleOutgoingXml))
        )

        running(newApp) {
          val sut    = newApp.injector.instanceOf[MessagesController]
          val result = sut.outgoing(EoriNumber("GB0123456789"), MovementType("departures"), movementId, messageId)(apiRequest)

          Helpers.status(result) mustBe INTERNAL_SERVER_ERROR
        }
      }
    }

    "large messages" - {

      "with a large valid body routing to GB, return 202" in {
        // We do this instead of using the standard "app" because we otherwise get the error
        // "Trying to materialize stream after materializer has been shutdown".
        // We suspect it's due to nested tests.
        val newApp                  = appBuilder.build()
        val conversationId          = ConversationId(UUID.randomUUID())
        val (movementId, messageId) = conversationId.toMovementAndMessageId

        server.stubFor(
          post(
            urlEqualTo("/gb")
          )
            .willReturn(aResponse().withStatus(INTERNAL_SERVER_ERROR))
        )

        val expectedJson = Json.obj(
          "informationType" -> "S18",
          "file" -> Json.obj(
            "recipientOrSender" -> "ctc-forward",
            "name"              -> s"${conversationId.value.toString}-20230413-103441.xml",
            // 8464 is what is in the config, it's NOT the wiremock port
            "location" -> s"http://localhost:8464/object-store/object/sdes/${conversationId.value.toString}-20230413-103441.xml",
            "checksum" -> Json.obj(
              "value"     -> md5hash,
              "algorithm" -> "md5"
            ),
            "size" -> 1000,
            "properties" -> Json.arr(
              Json.obj("name" -> "x-conversation-id", "value" -> conversationId.value.toString)
            )
          ),
          "audit" -> Json.obj(
            "correlationID" -> SingleUUIDGenerator.uuid.toString
          )
        )

        server.stubFor(
          post(
            urlEqualTo("/sdes-stub/notification/fileready")
          ).withRequestBody(equalToJson(Json.stringify(expectedJson)))
            .withHeader(HeaderNames.CONTENT_TYPE, equalTo(MimeTypes.JSON))
            .withHeader("X-Client-Id", equalTo("CTC"))
            .willReturn(aResponse().withStatus(NO_CONTENT))
        )

        val apiRequest = FakeRequest(
          "POST",
          s"/traders/GB0123456789/movements/departures/${movementId.value}/messages/${messageId.value}",
          FakeHeaders(
            Seq(
              "x-message-type"         -> "IE015",
              "x-request-id"           -> UUID.randomUUID().toString,
              HeaderNames.CONTENT_TYPE -> MimeTypes.XML
            )
          ),
          Source.single(ByteString(sampleOutgoingLargeXml))
        )

        running(newApp) {
          val sut    = newApp.injector.instanceOf[MessagesController]
          val result = sut.outgoing(EoriNumber("GB0123456789"), MovementType("departures"), movementId, messageId)(apiRequest)

          Helpers.status(result) mustBe ACCEPTED
        }
      }
    }

    "with XML containing the incorrect message type (no reference number in the expected place), return 400" in {
      // We do this instead of using the standard "app" because we otherwise get the error
      // "Trying to materialize stream after materializer has been shutdown".
      // We suspect it's due to nested tests.
      val newApp                  = appBuilder.build()
      val conversationId          = ConversationId(UUID.randomUUID())
      val (movementId, messageId) = conversationId.toMovementAndMessageId

      val time      = OffsetDateTime.of(2023, 2, 14, 15, 55, 28, 0, ZoneOffset.UTC)
      val formatted = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss z", Locale.ENGLISH).withZone(ZoneOffset.UTC).format(time)

      val apiRequest = FakeRequest(
        "POST",
        s"/traders/GB0123456789/movements/departures/${movementId.value}/messages/${messageId.value}",
        FakeHeaders(
          Seq(
            "Date"                   -> formatted,
            "x-message-type"         -> "IE015",
            "x-request-id"           -> UUID.randomUUID().toString,
            HeaderNames.CONTENT_TYPE -> MimeTypes.XML
          )
        ),
        Source.single(ByteString(sampleIncomingXml)) // note this is the IE004, not the IE015
      )

      running(newApp) {
        val sut    = newApp.injector.instanceOf[MessagesController]
        val result = sut.outgoing(EoriNumber("GB0123456789"), MovementType("departures"), movementId, messageId)(apiRequest)

        Helpers.status(result) mustBe BAD_REQUEST
      }
    }

    "with broken XML, return 400" in {
      // We do this instead of using the standard "app" because we otherwise get the error
      // "Trying to materialize stream after materializer has been shutdown".
      // We suspect it's due to nested tests.
      val newApp                  = appBuilder.build()
      val conversationId          = ConversationId(UUID.randomUUID())
      val (movementId, messageId) = conversationId.toMovementAndMessageId

      val time      = OffsetDateTime.of(2023, 2, 14, 15, 55, 28, 0, ZoneOffset.UTC)
      val formatted = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss z", Locale.ENGLISH).withZone(ZoneOffset.UTC).format(time)

      val apiRequest = FakeRequest(
        "POST",
        s"/traders/GB0123456789/movements/departures/${movementId.value}/messages/${messageId.value}",
        FakeHeaders(
          Seq(
            "Date"                   -> formatted,
            "x-message-type"         -> "IE015",
            "x-request-id"           -> UUID.randomUUID().toString,
            HeaderNames.CONTENT_TYPE -> MimeTypes.XML
          )
        ),
        Source.single(ByteString(brokenXml))
      )

      running(newApp) {
        val sut    = newApp.injector.instanceOf[MessagesController]
        val result = sut.outgoing(EoriNumber("GB0123456789"), MovementType("departures"), movementId, messageId)(apiRequest)

        Helpers.status(result) mustBe BAD_REQUEST
      }
    }
  }

  "incoming" - {
    "when EIS makes a valid call with a valid body, return 201" - Seq("ABC", "123").foreach {
      authCode =>
        s"with auth code $authCode" in {
          // We do this instead of using the standard "app" because we otherwise get the error
          // "Trying to materialize stream after materializer has been shutdown".
          // We suspect it's due to nested tests.
          val newApp                  = appBuilder.build()
          val conversationId          = ConversationId(UUID.randomUUID())
          val (movementId, messageId) = conversationId.toMovementAndMessageId
          val outputMessageId         = Gen.stringOfN(16, Gen.hexChar.map(_.toLower)).sample.get
          val eoriNumber              = Gen.stringOfN(16, Gen.hexChar.map(_.toLower)).sample.get
          val clientId                = Gen.stringOfN(16, Gen.hexChar.map(_.toLower)).sample.get

          // We should hit the persistence layer on /transit-movements/traders/movements/${movementId.value}/messages?triggerId=messageId
          server.stubFor(
            post(new UrlPathPattern(new EqualToPattern(s"/transit-movements/traders/movements/${movementId.value}/messages"), false))
              .withQueryParam("triggerId", new EqualToPattern(messageId.value))
              .withHeader("x-message-type", new EqualToPattern("IE004"))
              .willReturn(
                aResponse().withStatus(OK).withBody(Json.stringify(Json.obj("messageId" -> outputMessageId, "eori" -> eoriNumber, "clientId" -> clientId)))
              )
          )

          server.stubFor(
            post(s"/transit-movements-push-notifications/traders/movements/${movementId.value}/messages/$outputMessageId/messageReceived")
              .willReturn(
                aResponse().withStatus(OK)
              )
          )

          val eisRequest = FakeRequest(
            "POST",
            s"/transit-movements-router/movement/${conversationId.value.toString}/messages",
            FakeHeaders(
              Seq(
                "Authorization"    -> s"Bearer $authCode",
                "x-correlation-id" -> UUID.randomUUID().toString
              )
            ),
            Source.single(ByteString(sampleIncomingXml))
          )

          running(newApp) {
            val sut    = newApp.injector.instanceOf[MessagesController]
            val result = sut.incomingViaEIS(conversationId)(eisRequest)

            Helpers.status(result) mustBe CREATED
          }
        }
    }

    "when EIS makes a call with a non response message, return 400" in {
      val newApp                  = appBuilder.build()
      val conversationId          = ConversationId(UUID.randomUUID())
      val (movementId, messageId) = conversationId.toMovementAndMessageId
      val outputMessageId         = Gen.stringOfN(16, Gen.hexChar.map(_.toLower)).sample.get
      val eoriNumber              = Gen.stringOfN(16, Gen.hexChar.map(_.toLower)).sample.get
      val clientId                = Gen.stringOfN(16, Gen.hexChar.map(_.toLower)).sample.get

      // We should hit the persistence layer on /transit-movements/traders/movements/${movementId.value}/messages?triggerId=messageId
      server.stubFor(
        post(new UrlPathPattern(new EqualToPattern(s"/transit-movements/traders/movements/${movementId.value}/messages"), false))
          .withQueryParam("triggerId", new EqualToPattern(messageId.value))
          .withHeader("x-message-type", new EqualToPattern("IE015"))
          .willReturn(
            aResponse().withStatus(OK).withBody(Json.stringify(Json.obj("messageId" -> outputMessageId, "eori" -> eoriNumber, "clientId" -> clientId)))
          )
      )

      server.stubFor(
        post(s"/transit-movements-push-notifications/traders/movements/${movementId.value}/messages/$outputMessageId/messageReceived")
          .willReturn(
            aResponse().withStatus(OK)
          )
      )

      val eisRequest = FakeRequest(
        "POST",
        s"/transit-movements-router/movement/${conversationId.value.toString}/messages",
        FakeHeaders(
          Seq(
            "Authorization"    -> s"Bearer ABC",
            "x-correlation-id" -> UUID.randomUUID().toString
          )
        ),
        Source.single(ByteString(sampleOutgoingLargeXml))
      )

      running(newApp) {
        val sut    = newApp.injector.instanceOf[MessagesController]
        val result = sut.incomingViaEIS(conversationId)(eisRequest)

        Helpers.status(result) mustBe BAD_REQUEST
      }
    }

    "when EIS makes a call with a non xml message, return 400" in {
      val newApp                  = appBuilder.build()
      val conversationId          = ConversationId(UUID.randomUUID())
      val (movementId, messageId) = conversationId.toMovementAndMessageId
      val outputMessageId         = Gen.stringOfN(16, Gen.hexChar.map(_.toLower)).sample.get
      val eoriNumber              = Gen.stringOfN(16, Gen.hexChar.map(_.toLower)).sample.get
      val clientId                = Gen.stringOfN(16, Gen.hexChar.map(_.toLower)).sample.get
      // We should hit the persistence layer on /transit-movements/traders/movements/${movementId.value}/messages?triggerId=messageId
      server.stubFor(
        post(new UrlPathPattern(new EqualToPattern(s"/transit-movements/traders/movements/${movementId.value}/messages"), false))
          .withQueryParam("triggerId", new EqualToPattern(messageId.value))
          .withHeader("x-message-type", new EqualToPattern("IE015"))
          .willReturn(
            aResponse().withStatus(OK).withBody(Json.stringify(Json.obj("messageId" -> outputMessageId, "eori" -> eoriNumber, "clientId" -> clientId)))
          )
      )

      server.stubFor(
        post(s"/transit-movements-push-notifications/traders/movements/${movementId.value}/messages/$outputMessageId/messageReceived")
          .willReturn(
            aResponse().withStatus(OK)
          )
      )

      val eisRequest = FakeRequest(
        "POST",
        s"/transit-movements-router/movement/${conversationId.value.toString}/messages",
        FakeHeaders(
          Seq(
            "Authorization"    -> s"Bearer ABC",
            "x-correlation-id" -> UUID.randomUUID().toString
          )
        ),
        Source.single(ByteString("Text somethings"))
      )

      running(newApp) {
        val sut    = newApp.injector.instanceOf[MessagesController]
        val result = sut.incomingViaEIS(conversationId)(eisRequest)

        Helpers.status(result) mustBe BAD_REQUEST
      }
    }

    "when EIS makes a call and Internal server error occurred, return 500" in {
      val newApp                  = appBuilder.build()
      val conversationId          = ConversationId(UUID.randomUUID())
      val (movementId, messageId) = conversationId.toMovementAndMessageId
      val outputMessageId         = Gen.stringOfN(16, Gen.hexChar.map(_.toLower)).sample.get
      val eoriNumber              = Gen.stringOfN(16, Gen.hexChar.map(_.toLower)).sample.get
      val clientId                = Gen.stringOfN(16, Gen.hexChar.map(_.toLower)).sample.get

      // We should hit the persistence layer on /transit-movements/traders/movements/${movementId.value}/messages?triggerId=messageId
      server.stubFor(
        post(new UrlPathPattern(new EqualToPattern(s"/transit-movements/traders/movements/${movementId.value}/messages"), false))
          .withQueryParam("triggerId", new EqualToPattern(messageId.value))
          .withHeader("x-message-type", new EqualToPattern("IE015"))
          .willReturn(
            aResponse().withStatus(OK).withBody(Json.stringify(Json.obj("messageId" -> outputMessageId, "eori" -> eoriNumber, "clientId" -> clientId)))
          )
      )

      server.stubFor(
        post(s"/transit-movements-push-notifications/traders/movements/${movementId.value}/messages/$outputMessageId/messageReceived")
          .willReturn(
            aResponse().withStatus(OK)
          )
      )
      val error = new ClassCastException()

      val eisRequest = FakeRequest(
        "POST",
        s"/transit-movements-router/movement/${conversationId.value.toString}/messages",
        FakeHeaders(
          Seq(
            "Authorization"    -> s"Bearer ABC",
            "x-correlation-id" -> UUID.randomUUID().toString
          )
        ),
        Source.failed(error)
      )

      running(newApp) {
        val sut    = newApp.injector.instanceOf[MessagesController]
        val result = sut.incomingViaEIS(conversationId)(eisRequest)

        Helpers.status(result) mustBe INTERNAL_SERVER_ERROR
      }
    }

    "when EIS makes a call with an invalid authorization header with an invalid body, return 400" in {
      val newApp         = appBuilder.build()
      val conversationId = ConversationId(UUID.randomUUID())

      val eisRequest = FakeRequest(
        "POST",
        s"/transit-movements-router/movement/${conversationId.value.toString}/messages",
        FakeHeaders(
          Seq(
            "Authorization"    -> s"Bearer ABC",
            "x-correlation-id" -> UUID.randomUUID().toString
          )
        ),
        Source.single(ByteString(brokenXml))
      )

      running(newApp) {
        val sut    = newApp.injector.instanceOf[MessagesController]
        val result = sut.incomingViaEIS(conversationId)(eisRequest)

        Helpers.status(result) mustBe BAD_REQUEST
      }
    }

    "when EIS makes a call with an invalid authorization header with a valid body, return 401" in forAll(Gen.stringOfN(4, Gen.alphaNumChar)) {
      authCode =>
        val conversationId = ConversationId(UUID.randomUUID())

        val eisRequest = FakeRequest(
          "POST",
          s"/transit-movements-router/movement/${conversationId.value.toString}/messages",
          FakeHeaders(
            Seq(
              "Authorization"    -> s"Bearer $authCode",
              "x-correlation-id" -> UUID.randomUUID().toString
            )
          ),
          Source.single(ByteString(sampleIncomingXml))
        )

        running(app) {
          val sut    = app.injector.instanceOf[MessagesController]
          val result = sut.incomingViaEIS(conversationId)(eisRequest)

          Helpers.status(result) mustBe UNAUTHORIZED
        }
    }
  }

}
