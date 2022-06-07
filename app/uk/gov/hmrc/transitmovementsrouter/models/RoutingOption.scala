package uk.gov.hmrc.transitmovementsrouter.models

import uk.gov.hmrc.transitmovementsrouter.config.AppConfig
import uk.gov.hmrc.transitmovementsrouter.config.EISInstanceConfig

object RoutingOption {
  case object Gb extends RoutingOption {
    override val code: String = "GB"
    override def config(appConfig: AppConfig): EISInstanceConfig = appConfig.eisGb
  }
  case object Ni extends RoutingOption {
    override val code: String = "NI"
    override def config(appConfig: AppConfig): EISInstanceConfig = appConfig.eisNi
  }
}
sealed trait RoutingOption {

  def code: String
  def config(appConfig: AppConfig): EISInstanceConfig

}
