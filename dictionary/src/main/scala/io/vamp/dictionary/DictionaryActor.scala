package io.vamp.dictionary

import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import io.vamp.common.akka._
import io.vamp.dictionary.DictionaryActor.Get
import io.vamp.dictionary.notification.{ DictionaryNotificationProvider, NoAvailablePortError, UnsupportedDictionaryRequest }
import io.vamp.model.artifact.DefaultScale

import scala.concurrent.Future
import scala.concurrent.duration._

object DictionaryActor {

  lazy val timeout = Timeout(ConfigFactory.load().getInt("vamp.dictionary.response-timeout").seconds)

  trait DictionaryMessage

  case class Get(key: String) extends DictionaryMessage

  val portAssignment = "vamp://routes/port?deployment=%s&port=%d"

  val hostResolver = "vamp://routes/host"

  val containerScale = "vamp://container/scale?deployment=%s&cluster=%s&service=%s"
}

case class DictionaryEntry(key: String, value: String)

class DictionaryActor extends CommonSupportForActors with DictionaryNotificationProvider {

  implicit val timeout = DictionaryActor.timeout

  private val portAssignment = toRegExp(DictionaryActor.portAssignment)
  private val portRange = ConfigFactory.load().getString("vamp.dictionary.port-range").split("-").map(_.toInt)
  private var currentPort = portRange(0) - 1
  private val hostResolver = toRegExp(DictionaryActor.hostResolver)
  private val containerScale = toRegExp(DictionaryActor.containerScale)

  private def toRegExp(string: String) = {
    val value = string.
      replaceAllLiterally("/", "\\/").
      replaceAllLiterally("?", "\\?").
      replaceAllLiterally("%s", "(.*?)").
      replaceAllLiterally("%d", "(\\d*?)")
    s"^$value$$".r
  }

  def receive = {
    case Get(key) ⇒ reply(Future.successful(get(key)))
    case any      ⇒ unsupported(UnsupportedDictionaryRequest(any))
  }

  private def get(key: String) = key match {
    case portAssignment(deployment, port) ⇒
      if (currentPort == portRange(1))
        reportException(NoAvailablePortError(portRange(0), portRange(1)))
      else {
        currentPort += 1
        currentPort
      }

    case hostResolver(_*) ⇒
      ConfigFactory.load().getString("vamp.gateway-driver.host")

    case containerScale(deployment, cluster, service) ⇒
      val config = ConfigFactory.load()
      val cpu = config.getDouble("vamp.dictionary.default-scale.cpu")
      val memory = config.getDouble("vamp.dictionary.default-scale.memory")
      val instances = config.getInt("vamp.dictionary.default-scale.instances")
      DefaultScale("", cpu, memory, instances)

    case value ⇒ value
  }
}