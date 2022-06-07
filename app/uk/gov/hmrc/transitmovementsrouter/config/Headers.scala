package uk.gov.hmrc.transitmovementsrouter.config

import play.api.ConfigLoader

object Headers {

  implicit val configLoader: ConfigLoader[Headers] = ConfigLoader {
    rootConfig => path =>
      Headers(rootConfig.getConfig(path).getString("bearerToken"))
  }

}
case class Headers(bearerToken: String)
