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

package uk.gov.hmrc.transitmovementsrouter.models.sdes

import org.scalacheck.Gen
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import uk.gov.hmrc.objectstore.client.Md5Hash
import uk.gov.hmrc.transitmovementsrouter.generators.TestModelGenerators

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

class FileMd5ChecksumSpec extends AnyFreeSpec with Matchers with ScalaCheckDrivenPropertyChecks with TestModelGenerators {

  "fromBase64(Md5Hash)" - {

    "for a file, store the location" in forAll(Gen.alphaNumStr) {
      string =>
        val messageDigest = MessageDigest.getInstance("MD5").digest(string.getBytes(StandardCharsets.UTF_8))
        val base64        = Base64.getEncoder.encodeToString(messageDigest)
        val hex           = messageDigest.map("%02x".format(_)).mkString
        FileMd5Checksum.fromBase64(Md5Hash(base64)).value mustBe hex
    }

    "ensure that the empty Md5 hash in Base64 returns the Hex equivalent" in {
      FileMd5Checksum.fromBase64(Md5Hash("1B2M2Y8AsgTpgAmY7PhCfg==")).value mustBe "d41d8cd98f00b204e9800998ecf8427e"
    }

  }

}
