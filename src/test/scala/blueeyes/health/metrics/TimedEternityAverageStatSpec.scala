package blueeyes.health.metrics

import blueeyes.json.JsonAST._
import org.specs.Specification

class TimedEternityAverageStatSpec extends Specification{
  private val clock = new Clock()
  "TimedEternityAverageStat" should{
    "creates JValue" in{
      val timedSample = TimedAverageStat(eternity)(clock.now _)
      fill(timedSample)

      val histogramValue = JArray(List(JDouble(4)))
      timedSample.toJValue mustEqual JObject(JField("perSecond", JObject(JField(eternity.toString, histogramValue) :: Nil)) :: Nil)
    }
  }

  private def fill(timedSample: Statistic[Long, Map[Long, Double]]){
    set(timedSample, 1001)
    set(timedSample, 1001)
    set(timedSample, 1002)
    set(timedSample, 1002)
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
}