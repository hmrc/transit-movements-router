package uk.gov.hmrc.transitmovementsrouter.config

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.inject.guice.GuiceApplicationBuilder

import java.time.LocalDateTime

class AppConfigSpec extends AnyWordSpec with Matchers {

  "AppConfig.defaultPhaseId" when {

    "time-based-phase-id is disabled" should {

      "return the configured default-phase-id" in {
        val config = configWith(
          phases = Seq(
            "phase-1" -> "2024-01-01T00:00:00",
            "phase-2" -> "2024-02-01T00:00:00"
          ),
          defaultPhaseId = "static-default",
          timeBasedEnabled = false
        )
        config.defaultPhaseId shouldBe "static-default"
      }
    }

    "time-based-phase-id is enabled" should {

      "fall back to default-phase-id when no phases are configured" in {
        val config = configWith(
          phases = Seq.empty,
          defaultPhaseId = "fallback",
          timeBasedEnabled = true
        )
        config.defaultPhaseId shouldBe "fallback"
      }

      "fall back to default-phase-id when every phase is in the future" in {
        val futureYear = LocalDateTime.now.getYear + 10
        val config     = configWith(
          phases = Seq(
            "phase-1" -> s"$futureYear-01-01T00:00:00",
            "phase-2" -> s"$futureYear-02-01T00:00:00"
          ),
          defaultPhaseId = "fallback",
          timeBasedEnabled = true
        )
        config.defaultPhaseId shouldBe "fallback"
      }

      "return the only phase when a single phase has started" in {
        val config = configWith(
          phases = Seq("only" -> "2020-01-01T00:00:00"),
          defaultPhaseId = "fallback",
          timeBasedEnabled = true
        )
        config.defaultPhaseId shouldBe "only"
      }

      "return the latest-started phase when multiple have started" in {
        val now    = LocalDateTime.now
        val config = configWith(
          phases = Seq(
            "oldest" -> now.minusYears(3).toString,
            "middle" -> now.minusYears(2).toString,
            "latest" -> now.minusYears(1).toString
          ),
          defaultPhaseId = "fallback",
          timeBasedEnabled = true
        )
        config.defaultPhaseId shouldBe "latest"
      }

      "ignore phases that haven't started yet and pick the latest one that has" in {
        val now    = LocalDateTime.now
        val config = configWith(
          phases = Seq(
            "started-earlier"  -> now.minusDays(10).toString,
            "started-recently" -> now.minusDays(1).toString,
            "not-yet"          -> now.plusDays(1).toString,
            "way-future"       -> now.plusYears(5).toString
          ),
          defaultPhaseId = "fallback",
          timeBasedEnabled = true
        )
        config.defaultPhaseId shouldBe "started-recently"
      }

      "sort phases regardless of the order they appear in config" in {
        val now    = LocalDateTime.now
        val config = configWith(
          phases = Seq(
            "latest" -> now.minusDays(1).toString,
            "oldest" -> now.minusYears(2).toString,
            "middle" -> now.minusMonths(6).toString
          ),
          defaultPhaseId = "fallback",
          timeBasedEnabled = true
        )
        config.defaultPhaseId shouldBe "latest"
      }

      "allow a repeated id to become active again later" in {
        val now    = LocalDateTime.now
        val config = configWith(
          phases = Seq(
            "phase-a" -> now.minusYears(2).toString,
            "phase-b" -> now.minusYears(1).toString,
            "phase-a" -> now.minusDays(1).toString
          ),
          defaultPhaseId = "fallback",
          timeBasedEnabled = true
        )
        config.defaultPhaseId shouldBe "phase-a"
      }
    }
  }

  private def configWith(
    phases: Seq[(String, String)] = Seq.empty,
    defaultPhaseId: String,
    timeBasedEnabled: Boolean
  ): AppConfig =
    GuiceApplicationBuilder()
      .configure(
        "metrics.enabled"     -> false,
        "auditing.enabled"    -> false,
        "default-phase-id"    -> defaultPhaseId,
        "time-based-phase-id" -> timeBasedEnabled,
        "phases"              -> phases.map { case (id, activeFrom) =>
          Map("id" -> id, "activeFrom" -> activeFrom)
        }.toList
      )
      .build()
      .injector
      .instanceOf[AppConfig]
}
