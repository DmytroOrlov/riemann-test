package controllers

import java.util.concurrent.atomic.{AtomicInteger, AtomicBoolean}
import javax.inject.Inject

import play.api._
import play.api.inject.ApplicationLifecycle
import play.api.mvc._
import net.benmur.riemann.client.RiemannClient.{riemannConnectAs, Reliable}
import net.benmur.riemann.client.ReliableIO._
import net.benmur.riemann.client.EventSenderDSL._
import net.benmur.riemann.client.EventDSL._

import akka.actor.{Cancellable, Actor, Props, ActorSystem}
import akka.util.Timeout
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import java.net.InetSocketAddress

import scala.util.Success

class Application @Inject()(implicit system: ActorSystem,
                            config: Configuration,
                            applicationLifecycle: ApplicationLifecycle) extends Controller {
  val connections = config getInt "reimann.connections" getOrElse 8
  val quantisation = config getInt "reimann.quantisation" getOrElse 20
  val eventsPerSecond = config getInt "reimann.eventsPerSecond" getOrElse 10000

  val logger = Logger(getClass)

  implicit val timeout = Timeout(1.second)
  implicit val ec = system.dispatcher

  val metricHost = ("127.0.0.1", 5555)
  val metrics = for (i <- 1 to connections)
    yield riemannConnectAs[Reliable] to new InetSocketAddress(metricHost._1, metricHost._2)
  val errorMetric = riemannConnectAs[Reliable] to new InetSocketAddress(metricHost._1, metricHost._2)

  val metricPart = host("Dmytros-Mac-13") | service("service") | metric(1)
  val errorMetricPart = host("Dmytros-Mac-13") | service("service-metricFailure") | metric(1)

  val pool = new AtomicInteger

  def index = Action {
    Ok("Ok")
  }

  object Tick

  private val tickActor = system.actorOf(Props(new Actor {
    val eventsPerTick = eventsPerSecond / quantisation / connections
    require(eventsPerTick > 0, "eventsPerTick is too small, you should increase quantisation")

    def receive = {
      case Tick => pool set eventsPerTick
    }
  }))

  def logFailure(f: Future[Boolean]): Unit = f.onComplete {
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
    require(period > 0, "period is too small, you should increase quantisation")

    scheduledAdder = Some(system.scheduler.schedule(
      1.second,
      period.millis,
      tickActor,
      Tick
    ))
    Future {
      while (true)
        if (pool.getAndDecrement() > 0)
          for (m <- metrics) logFailure(metricPart |>< m)
        else
          Thread.sleep(1)
    }
  }

  applicationLifecycle.addStopHook(() => Future.successful(stop()))
  start()
}
