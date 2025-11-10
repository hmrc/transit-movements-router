import sbt.*

object AppDependencies {

  private val catsVersion = "2.13.0"
  private val catsRetryVersion = "3.1.3"
  private val boostrapPlayVersion = "10.3.0"

  val compile: Seq[ModuleID] = Seq(
    "uk.gov.hmrc" %% "bootstrap-backend-play-30" % boostrapPlayVersion,
    "org.typelevel" %% "cats-core" % catsVersion,
    "com.github.cb372" %% "cats-retry" % catsRetryVersion,
    "uk.gov.hmrc.objectstore" %% "object-store-client-play-30" % "2.5.0",
    "com.github.cb372" %% "alleycats-retry" % catsRetryVersion,
    "org.apache.pekko" %% "pekko-slf4j" % "1.2.1",
    "org.apache.pekko" %% "pekko-connectors-xml" % "1.2.0",
    "io.lemonlabs" %% "scala-uri" % "4.0.3",
    "org.typelevel" %% "alleycats-core" % catsVersion,
    "uk.gov.hmrc" %% "internal-auth-client-play-30" % "4.3.0",
    "org.apache.pekko" %% "pekko-protobuf-v3" % "1.2.1",
    "org.apache.pekko" %% "pekko-serialization-jackson" % "1.2.1",
    "org.apache.pekko" %% "pekko-stream" % "1.2.1",
    "org.apache.pekko" %% "pekko-actor-typed" % "1.2.1"
  )

  val test: Seq[ModuleID] = Seq(
    "org.scalacheck" %% "scalacheck" % "1.19.0",
    "org.scalatestplus" %% "mockito-5-12" % "3.2.19.0",
    "org.scalatestplus" %% "scalacheck-1-18" % "3.2.19.0",
    "org.typelevel" %% "cats-core" % catsVersion,
    "com.github.cb372" %% "cats-retry" % catsRetryVersion,
    "com.github.cb372" %% "alleycats-retry" % catsRetryVersion,
    "uk.gov.hmrc" %% "bootstrap-test-play-30" % boostrapPlayVersion,
    "org.apache.pekko" %% "pekko-protobuf-v3" % "1.2.1",
    "org.apache.pekko" %% "pekko-serialization-jackson" % "1.2.1",
    "org.apache.pekko" %% "pekko-stream" % "1.2.1",
    "org.apache.pekko" %% "pekko-actor-typed" % "1.2.1"
  ).map(_ % Test)
}
