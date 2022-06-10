package uk.gov.hmrc.transitmovementsrouter.controllers

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.http.Status
import play.api.test.FakeRequest
import play.api.test.Helpers.defaultAwaitTimeout
import play.api.test.Helpers.status
import play.api.test.Helpers.stubControllerComponents
import uk.gov.hmrc.transitmovementsrouter.models.EORINumber

class MessagesControllerSpec extends AnyWordSpec with Matchers {

  val eori         = EORINumber("eori")
  val movementType = "departures"
  val movementId   = "ABC123"
  val messageId    = "XYZ456"
  val fakeRequest  = FakeRequest("POST", s"/traders/$eori/movements/$movementType/$movementId/messages/$messageId")
  val controller   = new MessagesController(stubControllerComponents())

  "GET /" should {
    "return 200" in {
      val result = controller.post(eori, movementType, movementId, messageId)(fakeRequest)
      status(result) shouldBe Status.OK
    }
  }
}
