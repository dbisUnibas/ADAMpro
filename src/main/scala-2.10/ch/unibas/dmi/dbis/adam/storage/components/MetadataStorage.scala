package ch.unibas.dmi.dbis.adam.storage.components

import ch.unibas.dmi.dbis.adam.entity.Entity._
import org.apache.spark.sql.types.DataType
import org.apache.spark.sql.{DataFrame, SaveMode}

/**
 * adamtwo
 *
 * Ivan Giangreco
 * October 2015
 */
trait MetadataStorage {
  val idColumnName = "__ADAMTWO_TID"

  /**
    * Create entity in metadata storage.
    *
    * @param entityname
    * @param fields
    */
  def create(entityname : EntityName, fields : Map[String, DataType]) : Boolean

  /**
    * Read data from metadata storage.
    *
    * @param tablename
    * @return
    */
  def read(tablename: EntityName): DataFrame

  /**
    * Write data to metadata storage.
    *
    * @param tablename
    * @param data
    * @param mode
    * @return
    */
  def write(tablename : EntityName, data: DataFrame, mode : SaveMode = SaveMode.Append): Boolean

  /**
    * Drop data from the metadata storage.
    *
    * @param tablename
    * @return
    */
  def drop(tablename :EntityName) : Boolean
}
