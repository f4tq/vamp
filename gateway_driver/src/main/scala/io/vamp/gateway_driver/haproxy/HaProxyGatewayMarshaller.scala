package io.vamp.gateway_driver.haproxy

import io.vamp.common.crypto.Hash
import io.vamp.gateway_driver.GatewayMarshaller
import io.vamp.gateway_driver.haproxy.txt.HaProxyConfigurationTemplate
import io.vamp.model.artifact._

object HaProxyGatewayMarshaller {

  val path: List[String] = "haproxy" :: Nil

  private val referenceMatcher = """^[a-zA-Z0-9][a-zA-Z0-9.\-_]{3,63}$""".r

  def name(gateway: Gateway, path: GatewayPath = Nil) = {
    def path2string(path: GatewayPath): String = {
      path.segments match {
        case Nil                   ⇒ ""
        case some if some.size < 4 ⇒ path2string(some :+ "-")
        case some                  ⇒ some.mkString("/")
      }
    }

    path2string(path) match {
      case ""  ⇒ flatten(path2string(gateway.name))
      case str ⇒ flatten(s"${path2string(gateway.name)}/$str")
    }
  }

  private def flatten(string: String) = Flatten.flatten(string) match {
    case referenceMatcher(_*) ⇒ string
    case _                    ⇒ Hash.hexSha1(string)
  }
}

trait HaProxyGatewayMarshaller extends GatewayMarshaller {

  import HaProxyGatewayMarshaller._
  import io.vamp.model.artifact.DefaultFilter._

  private val socketPath = "/opt/vamp"

  override val path = HaProxyGatewayMarshaller.path

  override def info: AnyRef = "HAProxy v1.5.x"

  override def marshall(gateways: List[Gateway]): String = HaProxyConfigurationTemplate(convert(gateways)).body.replaceAll("\\\n\\s*\\\n\\s*\\\n", "\n\n")

  private[haproxy] def convert(gateways: List[Gateway]): HaProxy = {
    gateways.map(convert).reduceOption((m1, m2) ⇒ m1.copy(m1.frontends ++ m2.frontends, m1.backends ++ m2.backends)).getOrElse(HaProxy(Nil, Nil))
  }

  private[haproxy] def convert(gateway: Gateway): HaProxy = backends(gateway) match {
    case backend ⇒ HaProxy(frontends(backend)(gateway), backend)
  }

  private def frontends(backends: List[Backend])(implicit gateway: Gateway): List[Frontend] = {
    def backendFor(name: String) = backends.find(_.name == name).getOrElse(throw new IllegalArgumentException(s"No backend: $name"))

    Frontend(
      name = name(gateway),
      bindIp = Option("0.0.0.0"),
      bindPort = Option(gateway.port.number),
      mode = mode,
      unixSock = None,
      sockProtocol = None,
      options = Options(),
      filters = filters,
      defaultBackend = backendFor(name(gateway))) :: gateway.routes.map { route ⇒
        Frontend(
          name = name(gateway, route.path),
          bindIp = None,
          bindPort = None,
          mode = mode,
          unixSock = Option(unixSocket(route)),
          sockProtocol = Option("accept-proxy"),
          options = Options(),
          filters = Nil,
          defaultBackend = backendFor(name(gateway, route.path)))
      }
  }

  private def backends(implicit gateway: Gateway): List[Backend] = Backend(
    name = name(gateway),
    mode = mode,
    proxyServers = gateway.routes.map {
      case route: AbstractRoute ⇒
        ProxyServer(
          name = name(gateway, route.path),
          unixSock = unixSocket(route),
          weight = route.weight.get
        )
      case route ⇒ throw new IllegalArgumentException(s"Unsupported route: $route")
    },
    servers = Nil,
    sticky = gateway.sticky.contains(Gateway.Sticky.Service) || gateway.sticky.contains(Gateway.Sticky.Instance),
    options = Options()) :: gateway.routes.map {
      case route: DeployedRoute ⇒
        Backend(
          name = name(gateway, route.path),
          mode = mode,
          proxyServers = Nil,
          servers = route.targets.map { instance ⇒
            Server(
              name = instance.name,
              host = instance.host,
              port = instance.port,
              weight = 100)
          },
          sticky = gateway.sticky.contains(Gateway.Sticky.Instance),
          options = Options())
      case route ⇒ throw new IllegalArgumentException(s"Unsupported route: $route")
    }

  private def filters(implicit gateway: Gateway): List[Filter] = gateway.routes.flatMap {
    case route: AbstractRoute ⇒ route.filters.map(f ⇒ filter(route, f.asInstanceOf[DefaultFilter]))
    case _                    ⇒ Nil
  }

  private[haproxy] def filter(route: Route, filter: DefaultFilter)(implicit gateway: Gateway): Filter = {
    val (condition, negate) = filter.condition match {
      case userAgent(n, c)        ⇒ s"hdr_sub(user-agent) ${c.trim}" -> (n == "!")
      case host(n, c)             ⇒ s"hdr_str(host) ${c.trim}" -> (n == "!")
      case cookieContains(c1, c2) ⇒ s"cook_sub(${c1.trim}) ${c2.trim}" -> false
      case hasCookie(c)           ⇒ s"cook(${c.trim}) -m found" -> false
      case missesCookie(c)        ⇒ s"cook_cnt(${c.trim}) eq 0" -> false
      case headerContains(h, c)   ⇒ s"hdr_sub(${h.trim}) ${c.trim}" -> false
      case hasHeader(h)           ⇒ s"hdr_cnt(${h.trim}) gt 0" -> false
      case missesHeader(h)        ⇒ s"hdr_cnt(${h.trim}) eq 0" -> false
      case any                    ⇒ any -> false
    }

    val name = if (filter.name.isEmpty) Hash.hexSha1(condition).substring(0, 16) else filter.name

    Filter(name, condition, HaProxyGatewayMarshaller.name(gateway, route.path), negate)
  }

  private def mode(implicit gateway: Gateway) = if (gateway.port.`type` == Port.Type.Http) Mode.http else Mode.tcp

  private def unixSocket(route: Route)(implicit gateway: Gateway) = s"$socketPath/${Hash.hexSha1(HaProxyGatewayMarshaller.name(gateway, route.path))}.sock"
}
