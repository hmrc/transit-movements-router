import sbt.*

object AppDependencies {

  private val catsVersion = "2.12.0"
  private val catsRetryVersion = "3.1.3"
  private val boostrapPlayVersion = "9.11.0"

  val compile: Seq[ModuleID] = Seq(
    "uk.gov.hmrc" %% "bootstrap-backend-play-30" % boostrapPlayVersion,
    "org.typelevel" %% "cats-core" % catsVersion,
    "com.github.cb372" %% "cats-retry" % catsRetryVersion,
    "uk.gov.hmrc.objectstore" %% "object-store-client-play-30" % "2.1.0",
    "com.github.cb372" %% "alleycats-retry" % catsRetryVersion,
    "org.apache.pekko" %% "pekko-slf4j" % "1.1.2",
    "org.apache.pekko" %% "pekko-connectors-xml" % "1.0.2",
    "io.lemonlabs" %% "scala-uri" % "4.0.3",
    "org.typelevel" %% "alleycats-core" % catsVersion,
    "uk.gov.hmrc" %% "internal-auth-client-play-30" % "3.1.0",
    "org.apache.pekko" %% "pekko-protobuf-v3" % "1.1.2",
    "org.apache.pekko" %% "pekko-serialization-jackson" % "1.1.2",
    "org.apache.pekko" %% "pekko-stream" % "1.1.2",
    "org.apache.pekko" %% "pekko-actor-typed" % "1.1.2"
  )

  val test: Seq[ModuleID] = Seq(
    "org.scalacheck" %% "scalacheck" % "1.18.1",
    "org.scalatestplus" %% "mockito-5-12" % "3.2.19.0",
    "org.scalatestplus" %% "scalacheck-1-18" % "3.2.19.0",
    "org.typelevel" %% "cats-core" % catsVersion,
    "com.github.cb372" %% "cats-retry" % catsRetryVersion,
    "com.github.cb372" %% "alleycats-retry" % catsRetryVersion,
    "uk.gov.hmrc" %% "bootstrap-test-play-30" % boostrapPlayVersion,
    "org.apache.pekko" %% "pekko-protobuf-v3" % "1.1.2",
    "org.apache.pekko" %% "pekko-serialization-jackson" % "1.1.2",
    "org.apache.pekko" %% "pekko-stream" % "1.1.2",
    "org.apache.pekko" %% "pekko-actor-typed" % "1.1.2"
  ).map(_ % Test)
}
