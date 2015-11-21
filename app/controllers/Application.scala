package controllers

import javax.inject.Inject

import play.api._
import play.api.inject.ApplicationLifecycle
import play.api.mvc._
import net.benmur.riemann.client.RiemannClient.{riemannConnectAs, Reliable}
import net.benmur.riemann.client.ReliableIO._
import net.benmur.riemann.client.EventSenderDSL._
import net.benmur.riemann.client.EventDSL._

import akka.actor.{Cancellable, ActorSystem}
import akka.util.Timeout
import scala.concurrent.Future
import scala.concurrent.duration.{Duration, DurationInt}
import java.net.InetSocketAddress

import scala.util.Success

class Application @Inject()(implicit system: ActorSystem,
                            config: Configuration,
                            applicationLifecycle: ApplicationLifecycle) extends Controller {

  import config.underlying

  val eventsPerSecond = underlying getInt "reimann.eventsPerSecond"
  val connections = underlying getInt "reimann.connections"
  val quantisation = config getInt "reimann.quantisation" getOrElse 1
  val riemannRemote = new InetSocketAddress(underlying getString "reimann.host", underlying getInt "reimann.port")

  implicit val timeout = Timeout((underlying getInt "reimann.timeout").seconds)
  implicit val ec = system.dispatcher

  val metrics = for (i <- 1 to connections)
    yield riemannConnectAs[Reliable] to riemannRemote
  val errorMetric = riemannConnectAs[Reliable] to riemannRemote

  val metricPart = host("Dmytros-Mac-13") | service("service") | metric(1)
  val errorMetricPart = host("Dmytros-Mac-13") | service("service-metricFailure") | metric(1)

  def index = Action {
    Ok("Ok")
  }

  val eventsPerTick = eventsPerSecond / quantisation / connections
  require(eventsPerTick > 0, "eventsPerTick must be >= connections")

  def sendMetricBatch() = for {
    _ <- 1 to eventsPerTick
    m <- metrics
  } (metricPart |>< m).onComplete {
      case Success(true) =>
      case _ => errorMetricPart |>> errorMetric
    }

  private var scheduledAdder: Option[Cancellable] = None

  def stop(): Unit = {
    scheduledAdder foreach (_.cancel())
    scheduledAdder = None
  }

  def start(): Unit = {
    val period = 1000 / quantisation
    require(period > 0, "period must be > 0, decrease quantisation")

    scheduledAdder = Some(system.scheduler.schedule(Duration.Zero, period.millis)(sendMetricBatch()))
  }

  applicationLifecycle.addStopHook(() => Future.successful(stop()))
  start()
}
