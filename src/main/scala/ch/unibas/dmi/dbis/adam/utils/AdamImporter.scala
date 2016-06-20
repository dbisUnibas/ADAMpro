package ch.unibas.dmi.dbis.adam.utils

import java.sql.{Connection, DriverManager}

import ch.unibas.dmi.dbis.adam.api.{IndexOp, EntityOp}
import ch.unibas.dmi.dbis.adam.config.AdamConfig
import ch.unibas.dmi.dbis.adam.datatypes.FieldTypes
import ch.unibas.dmi.dbis.adam.datatypes.FieldTypes.FEATURETYPE
import ch.unibas.dmi.dbis.adam.datatypes.feature.FeatureVectorWrapper
import ch.unibas.dmi.dbis.adam.entity.AttributeDefinition
import ch.unibas.dmi.dbis.adam.main.{AdamContext, SparkStartup}
import ch.unibas.dmi.dbis.adam.query.distance.NormBasedDistanceFunction
import org.apache.spark.Logging
import org.apache.spark.annotation.Experimental
import org.apache.spark.sql.jdbc.AdamDialectRegistrar
import org.apache.spark.sql.types.DataTypes

import scala.collection.mutable.ListBuffer
import scala.util.Random

/**
  * adamtwo
  *
  * Ivan Giangreco
  * May 2016
  */
@Experimental
class AdamImporter(url: String, user: String, password: String) extends Logging {
  AdamDialectRegistrar.register(url)

  private def openConnection(): Connection = {
    DriverManager.getConnection(url, user, password)
  }

  def importTable(schemaname: String, tablename: String, newtablename: String)(implicit ac: AdamContext) {
    try {
      log.info("importing table " + tablename)

      val attributeRenamingRules = Seq(("shotid" -> "id"), ("video" -> "multimediaobject"), ("startframe" -> "segmentstart"), ("endframe" -> "segmentend"), ("frames" -> "framecount"), ("seconds" -> "duration"))
      val attributeCasting = Seq(("id" -> DataTypes.StringType), ("multimediaobject" -> DataTypes.StringType))

      val df = ac.sqlContext.read.format("jdbc").options(
        Map("url" -> url, "dbtable" -> (schemaname + "." + tablename), "user" -> AdamConfig.jdbcUser, "password" -> AdamConfig.jdbcPassword, "driver" -> "org.postgresql.Driver")
      ).load()

      val conn = openConnection()

      val pkResult = conn.createStatement().executeQuery(
        "SELECT a.attname FROM pg_index i JOIN   pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY(i.indkey) WHERE  i.indrelid = '" + schemaname + "." + tablename + "'::regclass AND i.indisprimary;")
      pkResult.next()
      val pk = {
        val adamPK = pkResult.getString(1)

        val renamedPK = attributeRenamingRules.filter(_._1 == adamPK)
        if (!renamedPK.isEmpty) {
          renamedPK.head._2
        } else {
          adamPK
        }
      }

      val featureResult = conn.createStatement().executeQuery(
        "SELECT column_name FROM information_schema.columns WHERE table_schema = '" + schemaname + "' AND table_name = '" + tablename + "' AND data_type = 'feature';")
      val lb = ListBuffer[String]()
      while (featureResult.next()) {
        lb.append(featureResult.getString(1))
      }
      val featureFields = lb.toSeq


      var insertDF = df

      import org.apache.spark.sql.functions.udf
      val toFeatureVectorWrapper = udf((c: String) => {
        val vector = c.substring(1, c.length - 1).split(",").map(_.toFloat)
        new FeatureVectorWrapper(vector)
      })

      df.schema.fields.filter(x => featureFields.contains(x.name)).map { field =>
        val newName = field.name + "__" + math.abs(Random.nextInt)
        insertDF = insertDF
          .withColumn("feature", toFeatureVectorWrapper(insertDF(field.name)))
          .drop(field.name)
      }

      attributeRenamingRules.foreach {
        rule => insertDF = insertDF.withColumnRenamed(rule._1, rule._2)
      }


      attributeCasting.foreach {
        rule =>
          if (insertDF.schema.fieldNames.contains(rule._1)) {
            insertDF = insertDF.withColumn(rule._1, insertDF.col(rule._1).cast(rule._2))
          }
      }

      if (tablename == "videos") {
        import org.apache.spark.sql.functions.lit
        insertDF = insertDF.withColumn("type", lit(0))
      }

      var schema = insertDF.schema.fields.map(field => {
        if (featureFields.contains(field.name)) {
          AttributeDefinition("feature", FEATURETYPE, field.name.equals(pk))
        } else {
          val fieldType = FieldTypes.fromDataType(field.dataType)
          AttributeDefinition(field.name, fieldType, field.name.equals(pk))
        }
      })


      val entityname = schemaname + "_" + newtablename
      log.info("creating new entity " + entityname + " with schema: " + schema.map(field => field.name + "(" + field.fieldtype.name + ")").mkString(", "))
      val entity = EntityOp.create(entityname, schema, true)

      if (entity.isSuccess) {
        log.info("importing to new entity " + entityname + " with schema: " + insertDF.schema.toString())
        EntityOp.insert(entity.get.entityname, insertDF)

        val insertDFcount = insertDF.count
        val entitycount = EntityOp.count(entityname).get
        if (insertDFcount != entitycount) {
          log.error("missing some tuples in entity " + entityname + "; in df: " + insertDFcount + ", inserted: " + entitycount)
        }
        log.info("successfully imported data into entity " + entityname + "; in df: " + insertDFcount + ", inserted: " + entitycount)
        assert(insertDFcount == entitycount)

        IndexOp.create(entityname, "feature", "vaf", NormBasedDistanceFunction(2))
      } else {
        log.error("entity not created", entity.failed.get)
      }
    } catch {
      case e: Exception => log.error("error when importing table " + tablename, e)
    }
  }
}

object AdamImporter {
  def apply(host: String, database: String, username: String, password: String)(implicit ac: AdamContext): Unit = {
    val importer = new AdamImporter("jdbc:postgresql://" + host + "/" + database, username, password)
    val entityRenamingRules = Seq(("shots" -> "segment"), ("videos" -> "multimediaobject")).toMap


    //tables to import

    val cineast = Seq("representativeframes", "resultcachenames", "shots", "videos")
    cineast.map(table => importer.importTable("cineast", table, entityRenamingRules.getOrElse(table, table)))

    val features = Seq("averagecolor", "averagecolorarp44", "averagecolorarp44normalized", "averagecolorcld", "averagecolorcldnormalized", "averagecolorgrid8", "averagecolorgrid8normalized", "averagecolorraster", "averagefuzzyhist", "averagefuzzyhistnormalized", "chromagrid8", "cld", "cldnormalized", "contrast", "dominantcolors", "dominantedgegrid16", "dominantedgegrid8", "edgearp88", "edgearp88full", "edgegrid16", "edgegrid16full", "ehd", "huevaluevariancegrid8", "mediancolor", "mediancolorarp44", "mediancolorarp44normalized", "mediancolorgrid8", "mediancolorgrid8normalized", "mediancolorraster", "medianfuzzyhist", "medianfuzzyhistnormalized", "motionhistogram", "saturationandchroma", "saturationgrid8", "simpleperceptualhash", "stmp7eh", "subdivaveragefuzzycolor", "subdivmedianfuzzycolor", "subdivmotionhistogram2", "subdivmotionhistogram3", "subdivmotionhistogram4", "subdivmotionhistogram5", "surf", "surffull")
    features.map(table => importer.importTable("features", table, entityRenamingRules.getOrElse(table, table)))

    val concepts = Seq("captioned")
    concepts.map(table => importer.importTable("captions", table, entityRenamingRules.getOrElse(table, table)))
  }

  def main(args: Array[String]): Unit = {
    //for experimental reasons only
    val ac = SparkStartup.mainContext

    if (args.length != 4) {
      exit(1)
    }

    apply(args(0), args(1), args(2), args(3))(ac)
  }
}
