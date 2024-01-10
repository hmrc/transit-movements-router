import play.sbt.routes.RoutesKeys
import uk.gov.hmrc.DefaultBuildSettings

val appName = "transit-movements-router"

ThisBuild / majorVersion := 0
ThisBuild / scalaVersion := "2.13.12"

lazy val microservice = Project(appName, file("."))
  .enablePlugins(PlayScala, SbtDistributablesPlugin)
  .disablePlugins(JUnitXmlReportPlugin)
  .settings(
    PlayKeys.playDefaultPort := 9478,
    libraryDependencies ++= AppDependencies.compile ++ AppDependencies.test,
    scalacOptions += "-Wconf:src=routes/.*:s",
    RoutesKeys.routesImport ++= Seq(
      "uk.gov.hmrc.transitmovementsrouter.models._"
    )
  )
  .settings(resolvers += Resolver.jcenterRepo)
  .settings(CodeCoverageSettings.settings: _*)
  .settings(inThisBuild(buildSettings))

lazy val it = project
  .enablePlugins(PlayScala)
  .dependsOn(microservice % "test->test") // the "test->test" allows reusing test code and test dependencies
  .settings(DefaultBuildSettings.itSettings())
  .settings(
    libraryDependencies ++= AppDependencies.test
  )
  .settings(CodeCoverageSettings.settings: _*)

// Settings for the whole build
lazy val buildSettings = Def.settings(
  scalafmtOnCompile := true
)
