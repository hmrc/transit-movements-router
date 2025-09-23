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

package uk.gov.hmrc.transitmovementsrouter.services

import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import org.scalacheck.Gen
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import uk.gov.hmrc.transitmovementsrouter.base.TestActorSystem

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Failure
import scala.util.Success
import scala.xml.XML

class EISMessageTransformerSpec extends AnyFreeSpec with Matchers with ScalaFutures with TestActorSystem with ScalaCheckDrivenPropertyChecks {

  val sut = new EISMessageTransformersImpl

  "unwrap" - {

    "should successfully transform valid xml" in {
      val input =
        """<n1:TraderChannelResponse xmlns:txd="http://ncts.dgtaxud.ec" xmlns:n1="http://www.hmrc.gov.uk/eis/ncts5/v1" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://www.hmrc.gov.uk/eis/ncts5/v1 EIS_WrapperV10_TraderChannelSubmission-51.8.xsd"><txd:CC029C PhaseID="NCTS5.0">text</txd:CC029C></n1:TraderChannelResponse>"""

      val result = Source
        .single(ByteString.fromString(input))
        .via(sut.unwrap)
        .runReduce(_ ++ _)
        .map { x =>
          x.utf8String
        }

      whenReady(result) { r =>
        XML.loadString(r) mustBe <ncts:CC029C xmlns:ncts="http://ncts.dgtaxud.ec" PhaseID="NCTS5.0">text</ncts:CC029C>
      }
    }

    "should successfully transform with more complex valid xml, without specifying the PhaseID" in {
      val input =
        <n1:TraderChannelResponse xmlns:txd="http://ncts.dgtaxud.ec" xmlns:n1="http://www.hmrc.gov.uk/eis/ncts5/v1" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://www.hmrc.gov.uk/eis/ncts5/v1 EIS_WrapperV10_TraderChannelSubmission-51.8.xsd">
          <txd:CC015C>
            <messageSender>sender</messageSender>
            <messageReceiver>receiver</messageReceiver>
            <something>else</something>
          </txd:CC015C>
        </n1:TraderChannelResponse>.mkString

      val result = Source
        .single(ByteString.fromString(input))
        .via(sut.unwrap)
        .runReduce(_ ++ _)
        .map { x =>
          x.utf8String
        }

      whenReady(result) { r =>
        XML.loadString(r) mustBe <ncts:CC015C xmlns:ncts="http://ncts.dgtaxud.ec" PhaseID="NCTS5.1">
            <messageSender>sender</messageSender>
            <messageReceiver>receiver</messageReceiver>
            <something>else</something>
          </ncts:CC015C>
      }
    }

    "should successfully transform with more complex valid xml sent from EIS" in {
      val input =
        <n1:TraderChannelResponse xmlns:txd="http://ncts.dgtaxud.ec" xmlns:n1="http://www.hmrc.gov.uk/eis/ncts5/v1" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://www.hmrc.gov.uk/eis/ncts5/v1 EIS_WrapperV11_TraderChannelResponse-51.8.xsd">
          <txd:CC004C PhaseID="NCTS5.0">
            <messageSender>!</messageSender>
            <messageRecipient>!</messageRecipient>
            <preparationDateAndTime>0231-11-23T10:03:02</preparationDateAndTime>
            <messageIdentification>!</messageIdentification>
            <messageType>CC026C</messageType>
            <correlationIdentifier>!</correlationIdentifier>
            <TransitOperation>
              <LRN> </LRN>
              <MRN>00AA00000000000000</MRN>
              <amendmentSubmissionDateAndTime>0231-11-23T10:03:02</amendmentSubmissionDateAndTime>
              <amendmentAcceptanceDateAndTime>0231-11-23T10:03:02</amendmentAcceptanceDateAndTime>
            </TransitOperation>
            <CustomsOfficeOfDeparture>
              <referenceNumber>AA000000</referenceNumber>
            </CustomsOfficeOfDeparture>
            <HolderOfTheTransitProcedure>
              <identificationNumber> </identificationNumber>
              <TIRHolderIdentificationNumber> </TIRHolderIdentificationNumber>
              <name> </name>
              <Address>
                <streetAndNumber> </streetAndNumber>
                <postcode> </postcode>
                <city> </city>
                <country>AA</country>
              </Address>
            </HolderOfTheTransitProcedure>
          </txd:CC004C>
        </n1:TraderChannelResponse>.mkString

      val result = Source
        .single(ByteString.fromString(input))
        .via(sut.unwrap)
        .runReduce(_ ++ _)
        .map { x =>
          x.utf8String
        }

      whenReady(result) { r =>
        XML.loadString(r) mustBe <ncts:CC004C PhaseID="NCTS5.0" xmlns:ncts="http://ncts.dgtaxud.ec">
            <messageSender>!</messageSender>
            <messageRecipient>!</messageRecipient>
            <preparationDateAndTime>0231-11-23T10:03:02</preparationDateAndTime>
            <messageIdentification>!</messageIdentification>
            <messageType>CC026C</messageType>
            <correlationIdentifier>!</correlationIdentifier>
            <TransitOperation>
              <LRN> </LRN>
              <MRN>00AA00000000000000</MRN>
              <amendmentSubmissionDateAndTime>0231-11-23T10:03:02</amendmentSubmissionDateAndTime>
              <amendmentAcceptanceDateAndTime>0231-11-23T10:03:02</amendmentAcceptanceDateAndTime>
            </TransitOperation>
            <CustomsOfficeOfDeparture>
              <referenceNumber>AA000000</referenceNumber>
            </CustomsOfficeOfDeparture>
            <HolderOfTheTransitProcedure>
              <identificationNumber> </identificationNumber>
              <TIRHolderIdentificationNumber> </TIRHolderIdentificationNumber>
              <name> </name>
              <Address>
                <streetAndNumber> </streetAndNumber>
                <postcode> </postcode>
                <city> </city>
                <country>AA</country>
              </Address>
            </HolderOfTheTransitProcedure>
          </ncts:CC004C>
      }
    }

    "fail when xml isn't valid with no namespaces" in {
      val input = "<Wrapper>text</Wrapper>"

      val result =
        Source
          .single(ByteString.fromString(input))
          .via(sut.unwrap)
          .runReduce(_ ++ _)
          .map { x =>
            x.utf8String
          }

      whenReady(result.transform {
        case Success(_) => Failure(fail())
        case Failure(x) => Success(x)
      }) { res =>
        res mustBe a[Throwable]
      }

    }

    "fail when xml isn't valid with inner namespaces" in {
      val input = """<Wrapper><ncts:CC029C xmlns:ncts="http://ncts.dgtaxud.ec">text</ncts:CC029C></Wrapper>"""

      val result =
        Source
          .single(ByteString.fromString(input))
          .via(sut.unwrap)
          .runReduce(_ ++ _)
          .map { x =>
            x.utf8String
          }

      whenReady(result.transform {
        case Success(_) => Failure(fail())
        case Failure(x) => Success(x)
      }) { res =>
        res mustBe a[Throwable]
      }

    }

  }

  "wrap" - {
    "should successfully transform valid xml" in {
      val input =
        """<ncts:CC029C xmlns:ncts="http://ncts.dgtaxud.ec" PhaseID="NCTS5.0">text</ncts:CC029C>"""

      val result = Source
        .single(ByteString.fromString(input))
        .via(sut.wrap)
        .runReduce(_ ++ _)
        .map { x =>
          x.utf8String
        }

      whenReady(result) { r =>
          // format: off
          XML.loadString(r) mustBe
            <n1:TraderChannelSubmission xmlns:txd="http://ncts.dgtaxud.ec" xmlns:n1="http://www.hmrc.gov.uk/eis/ncts5/v1" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://www.hmrc.gov.uk/eis/ncts5/v1 EIS_WrapperV10_TraderChannelSubmission-51.8.xsd"><txd:CC029C PhaseID="NCTS5.0">text</txd:CC029C></n1:TraderChannelSubmission>
          // format: on
      }
    }

    "should successfully transform valid xml with a different namespace" in {
      // We do this instead of forAll as forAll seems to then use substrings of the prefix,
      // and this test fails when we have an empty string.
      val prefix = Gen.stringOfN(3, Gen.alphaLowerChar).sample.get
      val input  =
        s"""<$prefix:CC029C xmlns:$prefix="http://ncts.dgtaxud.ec" PhaseID="NCTS5.0">text</$prefix:CC029C>"""

      val result = Source
        .single(ByteString.fromString(input))
        .via(sut.wrap)
        .runReduce(_ ++ _)
        .map { x =>
          x.utf8String
        }

      whenReady(result) { r =>
          // format: off
          XML.loadString(r) mustBe
            <n1:TraderChannelSubmission xmlns:txd="http://ncts.dgtaxud.ec" xmlns:n1="http://www.hmrc.gov.uk/eis/ncts5/v1" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://www.hmrc.gov.uk/eis/ncts5/v1 EIS_WrapperV10_TraderChannelSubmission-51.8.xsd"><txd:CC029C PhaseID="NCTS5.0">text</txd:CC029C></n1:TraderChannelSubmission>
          // format: on
      }
    }

    "fail when xml isn't valid with no namespaces" in {
      val input = "<Wrapper>text</Wrapper>"

      val result =
        Source
          .single(ByteString.fromString(input))
          .via(sut.wrap)
          .runReduce(_ ++ _)
          .map { x =>
            x.utf8String
          }

      whenReady(result.transform {
        case Success(x) => Failure(fail(s"Success was found when it should not have succeeded. Output: $x"))
        case Failure(x) => Success(x)
      }) { res =>
        res mustBe a[Throwable]
      }

    }
  }
}
