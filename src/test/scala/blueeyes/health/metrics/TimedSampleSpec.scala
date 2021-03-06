package blueeyes.health.metrics

import org.specs.Specification
import IntervalLength._
import blueeyes.json.JsonAST._

class TimedSampleSpec extends Specification{
  private val clock = new Clock()
  "TimedSample" should{
    "create histogram" in{
      val timedSample = new TimedSampleImpl(interval(3.seconds, 7))(clock.now _)
      fill(timedSample)

      val histogram = timedSample.details
      histogram mustEqual(Map(94000 -> 0, 97000 -> 0, 100000 -> 4, 103000 -> 3, 106000 -> 0, 109000 -> 0, 112000 -> 6, 115000 -> 1))
    }
    "removes expired data" in{
      val timedSample = new TimedSampleImpl(interval(3.seconds, 3))(clock.now _)
      fill(timedSample)

      val histogram = timedSample.details
      histogram mustEqual(Map(106000 -> 0, 109000 -> 0, 112000 -> 6, 115000 -> 1))
    }
  }

  private def fill(timedSample: Statistic[Long, Map[Long, Double]]){
    set(timedSample, 100000)
    set(timedSample, 101000)
    set(timedSample, 102000)
    set(timedSample, 102100)

    set(timedSample, 103000)
    set(timedSample, 104000)
    set(timedSample, 104020)

    set(timedSample, 112000)
    set(timedSample, 112100)
    set(timedSample, 112020)

    set(timedSample, 114000)
    set(timedSample, 114100)
    set(timedSample, 114020)
    set(timedSample, 115000)
  }

  private def set(timedSample: Statistic[Long, Map[Long, Double]], now: Long) = {
    clock.setNow(now)
    timedSample += 1
  }

  class Clock{
    private var _now: Long = 0

    def now() = _now

    def setNow(value: Long){_now = value}
  }

  class TimedSampleImpl(intervalConfig: interval)(implicit clock: () => Long) extends TimedNumbersSample(intervalConfig)(clock){
    def toJValue = JNothing
  }
}