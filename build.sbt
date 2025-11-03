import play.sbt.routes.RoutesKeys
import uk.gov.hmrc.DefaultBuildSettings

val appName = "transit-movements-router"

ThisBuild / majorVersion := 0
ThisBuild / scalaVersion := "3.4.3"

lazy val microservice = Project(appName, file("."))
  .enablePlugins(PlayScala, SbtDistributablesPlugin)
  .disablePlugins(JUnitXmlReportPlugin)
  .settings(
    PlayKeys.playDefaultPort := 9478,
    libraryDependencies ++= AppDependencies.compile ++ AppDependencies.test,
    RoutesKeys.routesImport ++= Seq(
      "uk.gov.hmrc.transitmovementsrouter.models._"
    )
  )
  .settings(CodeCoverageSettings.settings *)
  .settings(inThisBuild(buildSettings))

lazy val it = project
  .enablePlugins(PlayScala)
  .dependsOn(microservice % "test->test") // the "test->test" allows reusing test code and test dependencies
  .settings(DefaultBuildSettings.itSettings())
  .settings(
    libraryDependencies ++= AppDependencies.test
  )
  .settings(CodeCoverageSettings.settings *)

// Settings for the whole build
lazy val buildSettings = Def.settings(
  scalafmtOnCompile := true
)
