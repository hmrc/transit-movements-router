import sbt.*

object AppDependencies {

  private val catsVersion         = "2.7.0"
  private val catsRetryVersion    = "3.1.0"
  private val boostrapPlayVersion = "8.6.0"

  val compile: Seq[ModuleID] = Seq(
    "uk.gov.hmrc"             %% "bootstrap-backend-play-30"    % boostrapPlayVersion,
    "org.typelevel"           %% "cats-core"                    % catsVersion,
    "com.github.cb372"        %% "cats-retry"                   % catsRetryVersion,
    "uk.gov.hmrc.objectstore" %% "object-store-client-play-30"  % "1.4.0",
    "com.github.cb372"        %% "alleycats-retry"              % catsRetryVersion,
    "org.apache.pekko"        %% "pekko-slf4j"                  % "1.0.1",
    "org.apache.pekko"        %% "pekko-connectors-xml"         % "1.0.1",
    "io.lemonlabs"            %% "scala-uri"                    % "3.6.0",
    "org.typelevel"           %% "alleycats-core"               % catsVersion,
    "uk.gov.hmrc"             %% "internal-auth-client-play-30" % "2.0.0"
  )

  val test: Seq[ModuleID] = Seq(
    "org.scalacheck"    %% "scalacheck"              % "1.16.0",
    "org.mockito"       %% "mockito-scala-scalatest" % "1.17.14",
    "org.scalatestplus" %% "mockito-3-2"             % "3.1.2.0",
    "org.scalatestplus" %% "scalacheck-1-14"         % "3.2.2.0",
    "org.typelevel"     %% "cats-core"               % catsVersion,
    "com.github.cb372"  %% "cats-retry"              % catsRetryVersion,
    "com.github.cb372"  %% "alleycats-retry"         % catsRetryVersion,
    "uk.gov.hmrc"       %% "bootstrap-test-play-30"  % boostrapPlayVersion
  ).map(_ % Test)
}
