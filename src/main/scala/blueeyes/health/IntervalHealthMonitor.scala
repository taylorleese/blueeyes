package blueeyes.health

import blueeyes.concurrent.Future
import metrics._
import scala.collection.JavaConversions._
import java.util.concurrent.ConcurrentHashMap
import collection.mutable.ConcurrentMap
import ConcurrentMaps._
import blueeyes.json.{JPathIndex, JPathNode, JPathField, JPath}
import blueeyes.json.JsonAST._
import blueeyes.json.MergeMonoid

import scalaz._
import Scalaz._

class IntervalHealthMonitor(val intervalConfig: IntervalConfig) extends HealthMonitor with FunctionsMonitor{

  private val _countsStats:   ConcurrentMap[JPath, Statistic[Long, Map[Long, Double]]]      = new ConcurrentHashMap[JPath, Statistic[Long, Map[Long, Double]]]
  private val _timersStats:   ConcurrentMap[JPath, Statistic[Long, Map[Long, Timer]]]       = new ConcurrentHashMap[JPath, Statistic[Long, Map[Long, Timer]]]
  private val _errorsStats:   ConcurrentMap[JPath, Statistic[Long, Map[Long, Double]]]      = new ConcurrentHashMap[JPath, Statistic[Long, Map[Long, Double]]]
  private val _sampleStats:   ConcurrentMap[JPath, Sample]                                  = new ConcurrentHashMap[JPath, Sample]
  private val _timedSampleStats:   ConcurrentMap[JPath, Statistic[Long, Map[Long, Double]]] = new ConcurrentHashMap[JPath, Statistic[Long, Map[Long, Double]]]
  private val _exportedStats: ConcurrentMap[JPath, ExportedStatistic[_]]                    = new ConcurrentHashMap[JPath, ExportedStatistic[_]]
  private implicit val mergeMonoid = MergeMonoid

  def request(path: JPath) { timedSampleStat(path) += 1 }

  def increment(path: JPath)(c: Long) { counterStat(path) += c }

  def count(path: JPath)              { increment(path)(1) }

  def trackTime(path: JPath)(ns: Long)   {timerStat(path).+=(ns)}

  def sample(path: JPath)(d: Double) { sampleStat(path) += d }

  def export[T](path: JPath, value: => T)(implicit converter: T => JValue) {createIfAbsent(path, _exportedStats, () => new ExportedStatistic[T](value))}

  def error[T <: Throwable](path: JPath)(t: T){
    errorStat(JPath(path.nodes ::: List(JPathField("errorDistribution"), JPathField(t.getClass.getName)))) += 1
  }

  def toJValue: JValue = {
    val statistics  = List[Map[JPath, Statistic[_, _]]](timerStats, sampleStats, countStats, exportedStats, timedSampleStats, errorStats)
    val map = statistics.map(composeStatistics(_))
    val jObjects     = map ::: List(errorsCountJValue)
    jObjects.asMA.sum
  }

  def countStats    = _countsStats.toMap

  def exportedStats = _exportedStats.toMap

  def timerStats    = _timersStats.toMap

  def sampleStats   = _sampleStats.toMap

  def timedSampleStats = _timedSampleStats.toMap

  def errorStats = _errorsStats.toMap

  private def errorsCountJValue = {
    val errorsCount       = _errorsStats.toMap.values.map(_.count).sum
    val errorsCountPath   = _errorsStats.headOption.map(v => JPath(v._1.nodes.take(v._1.nodes.length - 2) ::: List(JPathField("count"), JPathField(intervalConfig.toString))))
    errorsCountPath.map(jvalueToJObject(_, JInt(errorsCount))).getOrElse(JObject(Nil))
  }

  private def composeStatistics[T](stat: Map[JPath, Statistic[_, _]]) = {
    val jObjects = stat.toList.map(kv => jvalueToJObject(kv._1, kv._2.toJValue))
    jObjects.asMA.sum
  }

  private def jvalueToJObject(path: JPath, value: JValue): JObject = {
    def nodeName(node: JPathNode) = node match{
      case JPathField(name) => name
      case JPathIndex(index) => index.toString
    }

    val elements = path.nodes.reverse
    elements.tail.foldLeft(JObject(JField(nodeName(elements.head), value) :: Nil)){(result, element) => JObject(JField(nodeName(element), result) :: Nil)}
  }

  private val sampleSize = 1000

  private implicit def clock() = System.currentTimeMillis()
  private def timedSampleStat(path: JPath):  Statistic[Long, Map[Long, Double]] = createIfAbsent[JPath, Statistic[Long, Map[Long, Double]]](path, _timedSampleStats, newTimedSample(path) _)
  private def newTimedSample(path: JPath)(): Statistic[Long, Map[Long, Double]] = TimedAverageStat(intervalConfig)

  private def counterStat(path: JPath): Statistic[Long, Map[Long, Double]]      = createIfAbsent[JPath, Statistic[Long, Map[Long, Double]]](path, _countsStats, newCounter(path) _)
  private def newCounter(path: JPath)():  Statistic[Long, Map[Long, Double]]    = TimedCountStat(intervalConfig)

  private def errorStat(path: JPath): Statistic[Long, Map[Long, Double]]        = createIfAbsent[JPath, Statistic[Long, Map[Long, Double]]](path, _errorsStats, newErrorStat(path) _)
  private def newErrorStat(path: JPath)():  Statistic[Long, Map[Long, Double]]  = TimedErrorStat(intervalConfig)

  private def timerStat(path: JPath): Statistic[Long, Map[Long, Timer]]         = createIfAbsent[JPath, Statistic[Long, Map[Long, Timer]]](path, _timersStats, newTimer(path) _)
  private def newTimer(path: JPath)():  Statistic[Long, Map[Long, Timer]]       = TimedTimerStat(intervalConfig)

  private def sampleStat(path: JPath):  Sample    = createIfAbsent[JPath, Sample](path, _sampleStats, newSample _)
  private def newSample(): Sample = new Sample(sampleSize)
}
