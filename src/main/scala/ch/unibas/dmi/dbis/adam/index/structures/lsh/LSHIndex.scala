package ch.unibas.dmi.dbis.adam.index.structures.lsh

import ch.unibas.dmi.dbis.adam.config.FieldNames
import ch.unibas.dmi.dbis.adam.datatypes.bitString.BitString
import ch.unibas.dmi.dbis.adam.datatypes.feature.Feature._
import ch.unibas.dmi.dbis.adam.datatypes.feature.MovableFeature
import ch.unibas.dmi.dbis.adam.entity.Entity._
import ch.unibas.dmi.dbis.adam.index.Index
import ch.unibas.dmi.dbis.adam.index.Index.{IndexName, IndexTypeName}
import ch.unibas.dmi.dbis.adam.index.structures.IndexTypes
import ch.unibas.dmi.dbis.adam.index.structures.sh.SHResultHandler
import ch.unibas.dmi.dbis.adam.main.AdamContext
import ch.unibas.dmi.dbis.adam.query.Result
import ch.unibas.dmi.dbis.adam.query.distance.DistanceFunction
import ch.unibas.dmi.dbis.adam.query.query.NearestNeighbourQuery
import org.apache.spark.sql.DataFrame

import scala.collection.mutable.ListBuffer

/**
  * adamtwo
  *
  * Ivan Giangreco
  * August 2015
  */
class LSHIndex(val indexname: IndexName, val entityname: EntityName, override private[index] var data: DataFrame, private[index] val metadata: LSHIndexMetaData)(@transient implicit val ac: AdamContext)
  extends Index {

  override val indextypename: IndexTypeName = IndexTypes.LSHINDEX

  override val lossy: Boolean = true
  override val confidence = 0.toFloat

  override def scan(data: DataFrame, q: FeatureVector, distance: DistanceFunction, options: Map[String, Any], k: Int): Set[Result] = {
    log.debug("scanning LSH index " + indexname)

    val numOfQueries = options.getOrElse("numOfQ", "3").asInstanceOf[String].toInt

    import MovableFeature.conv_feature2MovableFeature
    val originalQuery = LSHUtils.hashFeature(q, metadata)
    val queries = ac.sc.broadcast(List.fill(numOfQueries)(LSHUtils.hashFeature(q.move(metadata.radius), metadata)) ::: List(originalQuery))

    val maxScore: Float = originalQuery.intersectionCount(originalQuery) * numOfQueries

    import org.apache.spark.sql.functions.udf
    val distUDF = udf((c: BitString[_]) => {
      var i = 0
      var score = 0
      while (i < queries.value.length) {
        val query = queries.value(i)
        score += c.intersectionCount(query)
        i += 1
      }

      score
    })

    val ids = ListBuffer[Result]()

    val localResults = data
      .withColumn(FieldNames.distanceColumnName, distUDF(data(FieldNames.featureIndexColumnName)))
      .rdd
      .mapPartitions { items =>
        val handler = new SHResultHandler(k)

        items.foreach(item => {
          handler.offer(item, this.pk.name)
        })

        handler.results.iterator
      }
      .collect()
      .groupBy(_.score)

    val it = localResults.keys.toSeq.sorted.reverseIterator

    while (it.hasNext && ids.length < k) {
      val id = it.next()
      val res = localResults(id)
      ids.append(localResults(id).map(res => Result(res.score.toFloat / maxScore, res.tid)).toSeq: _*)
    }

    log.debug("LSH index returning " + ids.length + " tuples")
    ids.toSet
  }

  override def isQueryConform(nnq: NearestNeighbourQuery): Boolean = {
    (nnq.distance.getClass == metadata.distance.getClass)
  }
}

object LSHIndex {
  def apply(indexname: IndexName, entityname: EntityName, data: DataFrame, meta: Any)(implicit ac: AdamContext): LSHIndex = {
    val indexMetaData = meta.asInstanceOf[LSHIndexMetaData]
    new LSHIndex(indexname, entityname, data, indexMetaData)
  }
}