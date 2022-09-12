import play.core.PlayVersion
import play.core.PlayVersion.current
import play.sbt.PlayImport._
import sbt.Keys.libraryDependencies
import sbt._

object AppDependencies {

  private val catsVersion         = "2.7.0"
  private val catsRetryVersion    = "3.1.0"
  private val boostrapPlayVersion = "6.2.0"

  val compile = Seq(
    "uk.gov.hmrc"        %% "bootstrap-backend-play-28" % boostrapPlayVersion,
    "org.typelevel"      %% "cats-core"                 % catsVersion,
    "com.github.cb372"   %% "cats-retry"                % catsRetryVersion,
    "com.github.cb372"   %% "alleycats-retry"           % catsRetryVersion,
    "com.typesafe.akka"  %% "akka-slf4j"                % PlayVersion.akkaVersion,
    "com.lightbend.akka" %% "akka-stream-alpakka-xml"   % "3.0.4",
    "io.lemonlabs"       %% "scala-uri"                 % "3.6.0",
    "org.typelevel"      %% "alleycats-core"            % catsVersion
  )

  val test = Seq(
    "org.scalatest"          %% "scalatest"               % "3.2.10",
    "com.typesafe.play"      %% "play-test"               % current,
    "com.vladsch.flexmark"    % "flexmark-all"            % "0.62.2",
    "org.scalatestplus.play" %% "scalatestplus-play"      % "4.0.3",
    "com.github.tomakehurst"  % "wiremock-standalone"     % "2.27.2",
    "org.scalacheck"         %% "scalacheck"              % "1.15.4",
    "org.mockito"            %% "mockito-scala-scalatest" % "1.17.5",
    "org.mockito"             % "mockito-core"            % "3.3.3",
    "org.scalatestplus"      %% "mockito-3-2"             % "3.1.2.0",
    "org.scalatestplus"      %% "scalacheck-1-14"         % "3.2.2.0",
    "org.typelevel"          %% "cats-core"               % catsVersion,
    "com.github.cb372"       %% "cats-retry"              % catsRetryVersion,
    "com.github.cb372"       %% "alleycats-retry"         % catsRetryVersion,
    "uk.gov.hmrc"            %% "bootstrap-test-play-28"  % boostrapPlayVersion
  ).map(_ % s"$Test, $IntegrationTest")
}
