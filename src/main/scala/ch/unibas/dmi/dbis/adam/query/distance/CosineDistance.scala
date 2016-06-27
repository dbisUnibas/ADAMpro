package ch.unibas.dmi.dbis.adam.query.distance

import breeze.linalg.norm
import ch.unibas.dmi.dbis.adam.datatypes.feature.Feature._
import ch.unibas.dmi.dbis.adam.query.distance.Distance._
import ch.unibas.dmi.dbis.adam.utils.Logging

/**
  * ADAMpro
  *
  * Ivan Giangreco
  * June 2016
  *
  * from Julia: 1 - dot(x, y) / (norm(x) * norm(y))
  */
object CosineDistance extends DistanceFunction with Logging with Serializable {
  override def apply(v1: FeatureVector, v2: FeatureVector, weights: Option[FeatureVector]): Distance = {
    if (weights.isDefined) {
      log.warn("weights cannot be used with cosine distance and are ignored")
    }

    if (math.abs(norm(v1, 2.0)) < 10E-6 || math.abs(norm(v2, 2.0)) < 10E-6) {
      log.warn("for vectors of elements 0 the cosine distance is not defined")
      0.toFloat //for convenience returning 0
    } else {
      (1.0 - (v1 dot v2) / (norm(v1, 2.0) * norm(v2, 2.0))).toFloat
    }
  }
}
