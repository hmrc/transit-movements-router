package uk.gov.hmrc.transitmovementsrouter.connectors

import akka.stream.scaladsl.Sink
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import org.scalacheck.Gen
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import play.api.http.Status.INTERNAL_SERVER_ERROR
import play.api.http.Status.NOT_FOUND
import play.api.http.Status.OK
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.test.HttpClientV2Support
import uk.gov.hmrc.transitmovementsrouter.it.base.TestActorSystem
import uk.gov.hmrc.transitmovementsrouter.it.base.WiremockSuite
import uk.gov.hmrc.transitmovementsrouter.models.responses.UpscanResponse.DownloadUrl
import uk.gov.hmrc.transitmovementsrouter.services.error.UpscanError

import scala.concurrent.ExecutionContext.Implicits.global

class UpscanConnectorSpec
    extends AnyFreeSpec
    with Matchers
    with WiremockSuite
    with TestActorSystem
    with HttpClientV2Support
    with ScalaFutures
    with ScalaCheckDrivenPropertyChecks {

  "streamFile" - {

    implicit val hc: HeaderCarrier = HeaderCarrier()

    "gets a stream when the file exists" in forAll(Gen.alphaNumStr) {
      string =>
        server.stubFor(
          get("/test.xml")
            .willReturn(aResponse().withStatus(OK).withBody(string))
        )

        val sut = new UpscanConnectorImpl(httpClientV2)
        val result = sut
          .streamFile(DownloadUrl(s"http://localhost:${server.port()}/test.xml"))
          .semiflatMap(
            stream => stream.reduce(_ ++ _).map(_.utf8String).runWith(Sink.head[String])
          )

        whenReady(result.value) {
          case Right(result) => result mustBe string
          case a             => fail(s"Expected Right($string), got $a")
        }
    }

    "returns a NotFound error when the file does not exist" in {
      server.stubFor(
        get("/test.xml")
          .willReturn(aResponse().withStatus(NOT_FOUND))
      )

      val sut = new UpscanConnectorImpl(httpClientV2)
      val result = sut
        .streamFile(DownloadUrl(s"http://localhost:${server.port()}/test.xml"))

      whenReady(result.value) {
        case Left(UpscanError.NotFound) => succeed
        case a                          => fail(s"Expected Left(NotFound), got $a")
      }
    }

    "returns a Unexpected error when a 500 is returned" in {
      server.stubFor(
        get("/test.xml")
          .willReturn(aResponse().withStatus(INTERNAL_SERVER_ERROR))
      )

      val sut = new UpscanConnectorImpl(httpClientV2)
      val result = sut
        .streamFile(DownloadUrl(s"http://localhost:${server.port()}/test.xml"))

      whenReady(result.value) {
        case Left(UpscanError.Unexpected(_)) => succeed
        case a                               => fail(s"Expected Left(Unexpected), got $a")
      }
    }

  }

}
