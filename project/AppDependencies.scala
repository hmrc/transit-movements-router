import play.core.PlayVersion
import play.core.PlayVersion.current
import play.sbt.PlayImport._
import sbt.Keys.libraryDependencies
import sbt._

object AppDependencies {

  private val catsVersion      = "2.7.0"
  private val catsRetryVersion = "3.1.0"

  val compile = Seq(
    "uk.gov.hmrc"             %% "bootstrap-backend-play-28"  % "5.24.0",
    "org.typelevel"           %% "cats-core"                  % catsVersion,
    "com.github.cb372"        %% "cats-retry"                 % catsRetryVersion,
    "com.github.cb372"        %% "alleycats-retry"            % catsRetryVersion
  )

  val test = Seq(
    "org.scalatest"          %% "scalatest"           % "3.2.10",
    "com.typesafe.play"      %% "play-test"           % current,
    "com.vladsch.flexmark"    % "flexmark-all"        % "0.62.2",
    "org.scalatestplus.play" %% "scalatestplus-play"  % "4.0.3",
    "com.github.tomakehurst"  % "wiremock-standalone" % "2.27.2",
    "org.scalacheck"         %% "scalacheck"          % "1.15.4",
    "org.mockito"             % "mockito-core"        % "3.3.3",
    "org.scalatestplus"      %% "mockito-3-2"         % "3.1.2.0",
    "org.scalatestplus"      %% "scalacheck-1-14"     % "3.2.2.0",
    "org.typelevel"          %% "cats-core"           % catsVersion,
    "com.github.cb372"       %% "cats-retry"          % catsRetryVersion,
    "com.github.cb372"       %% "alleycats-retry"     % catsRetryVersion
  ).map(_ % s"$Test, $IntegrationTest")
}
