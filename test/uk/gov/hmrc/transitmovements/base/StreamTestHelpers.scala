package uk.gov.hmrc.transitmovements.base

import akka.stream.alpakka.xml.ParseEvent
import akka.stream.alpakka.xml.scaladsl.XmlParsing
import akka.stream.scaladsl.Source
import akka.util.ByteString

import java.nio.charset.StandardCharsets
import scala.xml.NodeSeq

object StreamTestHelpers extends StreamTestHelpers

trait StreamTestHelpers {

  def createStream(node: NodeSeq): Source[ByteString, _] = createStream(node.mkString)

  def createStream(string: String): Source[ByteString, _] =
    Source.single(ByteString(string, StandardCharsets.UTF_8))

  def createParsingEventStream(node: NodeSeq): Source[ParseEvent, _] =
    createStream(node).via(XmlParsing.parser)
}
