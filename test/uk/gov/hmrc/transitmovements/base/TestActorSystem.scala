package uk.gov.hmrc.transitmovements.base

import akka.actor.ActorSystem
import akka.stream.Materializer
import org.scalatest.Suite

object TestActorSystem {
  val system: ActorSystem = ActorSystem("test")
}

trait TestActorSystem { self: Suite =>
  implicit val system: ActorSystem        = TestActorSystem.system
  implicit val materializer: Materializer = Materializer(TestActorSystem.system)
}
