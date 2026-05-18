package uk.gov.hmrc.transitmovementsrouter.models

import com.typesafe.config.Config
import play.api.ConfigLoader
import scala.jdk.CollectionConverters._

import java.time.LocalDateTime

case class Phase(id: String, activeFrom: LocalDateTime)

object Phase {
  implicit val configLoader: ConfigLoader[Phase] = (config: Config, path: String) => {
    val c = config.getConfig(path)
    Phase(
      c.getString("id"),
      LocalDateTime.parse(c.getString("activeFrom"))
    )
  }

  implicit val seqConfigLoader: ConfigLoader[Seq[Phase]] = (config: Config, path: String) =>
    config.getConfigList(path).asScala.toSeq.map { c =>
      Phase(
        c.getString("id"),
        LocalDateTime.parse(c.getString("activeFrom"))
      )
    }
}
