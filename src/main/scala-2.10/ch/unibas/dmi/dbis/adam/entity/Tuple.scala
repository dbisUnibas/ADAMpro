package ch.unibas.dmi.dbis.adam.entity

import ch.unibas.dmi.dbis.adam.datatypes.feature.Feature.FeatureVector
import ch.unibas.dmi.dbis.adam.datatypes.feature.{Feature, FeatureVectorWrapper}
import ch.unibas.dmi.dbis.adam.entity.Tuple.TupleID
import org.apache.spark.sql.Row

/**
 * adamtwo
 *
 * Ivan Giangreco
 * August 2015
 */
case class Tuple(val tid: TupleID, val feature: FeatureVector)

object Tuple {
  type TupleID = Long

  implicit def conv_row2tuple[T](value: Row): Tuple = {
    Tuple(value.getLong(0), value.getAs[FeatureVectorWrapper](1).vector)
  }
}