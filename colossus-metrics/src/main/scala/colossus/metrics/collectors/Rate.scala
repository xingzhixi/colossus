package colossus.metrics

import akka.actor._

import scala.concurrent.duration._

import EventLocality._

trait Rate extends EventCollector {

  def hit(tags: TagMap = TagMap.Empty, num: Int = 1)
}

case class RateParams(address: MetricAddress) extends MetricParams[Rate, RateParams] {
  def transformAddress(f: MetricAddress => MetricAddress) = copy(address = f(address))
}

object Rate {

  case class Hit(address: MetricAddress, tags: TagMap, count: Int = 1) extends MetricEvent

  def apply(address: MetricAddress): RateParams = RateParams(address)

  implicit object RateGenerator extends Generator[Rate, RateParams] {
    def local(params: RateParams, config: CollectorConfig): Local[Rate] = new ConcreteRate(params, config)

    def shared(params: RateParams, config: CollectorConfig)(implicit collector: ActorRef): Shared[Rate] = {
      new SharedRate(params, collector)
    }
  }
}

class BasicRate {

  private var _total: Long = 0L
  private var current: Long = 0L
  private var lastFullValue = 0L

  def total = _total

  def hit(num: Int = 1) {
    _total += num
    current += num
  }

  def tick() {
    lastFullValue = current
    current = 0
  }

  def value = lastFullValue

}

/**
 * Notice - the SharedRate is just a front for sending actor messages.  The
 * collector must be the actor that actually has access to the concrete rate,
 * where is should call it's "event" method when it receives this message
 *
 */
class SharedRate(val params: RateParams, collector: ActorRef) extends Rate with SharedLocality {
  def address = params.address
  def hit(tags: TagMap = TagMap.Empty, num: Int = 1) {
    collector ! Rate.Hit(address, tags, num)
  }
}

//notice this rate is not the actual core rate, since it handles tags
class ConcreteRate(params: RateParams, config: CollectorConfig) extends Rate with LocalLocality with TickedCollector {
  import collection.mutable.{Map => MutMap}

  private val rates = MutMap[TagMap, MutMap[FiniteDuration, BasicRate]]()

  def hit(tags: TagMap = TagMap.Empty, num: Int = 1){
    if (!rates.contains(tags)) {
      val r = MutMap[FiniteDuration, BasicRate]()
      config.intervals.foreach{p =>
        r(p) = new BasicRate
      }
      rates(tags) = r
    }
    rates(tags).foreach{_._2.hit(num)}
  }

  def address = params.address

  def tick(tickPeriod: FiniteDuration){
    rates.foreach{ case (tags, intervalValues) => intervalValues(tickPeriod).tick()}
  }

  def metrics(context: CollectionContext): MetricMap = {
    import MetricValues._
    val values = rates.map{case (tags, values) => 
      (tags ++ context.globalTags) -> SumValue(values(context.interval).value)
    }
    //totals are the same for each period
    val totals = rates.map{case (tags, values) => 
      (context.globalTags ++ tags, SumValue(values.head._2.total))
    }
    Map(params.address -> values.toMap, (params.address / "count") ->  totals.toMap)
  }

  def event: PartialFunction[MetricEvent, Unit] = {
    //argument for not including address in event
    case Rate.Hit(_, tags, num) => hit(tags, num)
  }
}


