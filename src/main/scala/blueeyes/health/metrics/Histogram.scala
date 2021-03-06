package blueeyes.health.metrics

import collection.mutable.HashMap
import scala.math.floor
import scala.math.round
import scala.math.max

trait Histogram[V]{
  def rawData: Map[Double, Long]

  def bucketInitialValue: V

  def setBucketValue(value: V, newValue: Long): V

  def build = {
    val sorted      = rawData.toList.sortWith((e1, e2) => (e1._1 < e2._1))
    val bucketSize  = bucket(sorted)

    val buckets = createBuckets(bucketSize, sorted)

    fillMissing(bucketSize, sorted, buckets).toMap
  }

  private def fillMissing(bucketSize: Int, sorted: List[(Double, Long)], buckets: HashMap[Long, V]): HashMap[Long, V] = {
    val minV          = floor(sorted.head._1).longValue
    val maxV          = floor(sorted.last._1).longValue
    var bucketNumber  = minV

    while (bucketNumber < maxV){
      val bucket = buckets.get(bucketNumber)

      if (!bucket.isDefined) buckets.put(bucketNumber, bucketInitialValue)

      bucketNumber = bucketNumber + bucketSize
    }

    buckets
  }

  private def createBuckets(bucketSize: Int, sorted: List[(Double, Long)]) = {
    val buckets     = new HashMap[Long, V]()
    val minV        = floor(sorted.head._1).longValue

    sorted.foreach(kv => {
      val bucketNumber: Long = minV + (floor((kv._1 - sorted.head._1) / bucketSize).longValue * bucketSize)

      val bucket      = buckets.get(bucketNumber)
      val bucketValue = setBucketValue(bucket.getOrElse(bucketInitialValue), kv._2)
      buckets.put(bucketNumber, bucketValue)
    })

    buckets
  }

  protected def bucket(sorted: List[(Double, Long)]) = {
    val minV = sorted.head._1
    val maxV = sorted.last._1

    max(floor(minV), round((maxV - minV) / sorted.size)).intValue
  }
}

trait SimpleHistogram extends Histogram[Double]{
  def bucketInitialValue = 0.0

  def setBucketValue(value: Double, newValue: Long) = value + newValue
}