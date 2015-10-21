package ch.unibas.dmi.dbis.adam.storage.catalog

import java.io._

import ch.unibas.dmi.dbis.adam.exception.{IndexExistingException, IndexNotExistingException, TableExistingException, TableNotExistingException}
import ch.unibas.dmi.dbis.adam.index.Index.{IndexName, IndexTypeName}
import ch.unibas.dmi.dbis.adam.index.structures.IndexStructures
import ch.unibas.dmi.dbis.adam.main.Startup
import ch.unibas.dmi.dbis.adam.table.Table.TableName
import slick.driver.H2Driver.api._
import slick.jdbc.meta.MTable

import scala.concurrent.Await
import scala.concurrent.duration._


/**
 * adamtwo
 *
 * Ivan Giangreco
 * August 2015
 */
object CatalogOperator {
  private val config = Startup.config
  private val db = Database.forURL("jdbc:h2:" + (config.catalogPath / "catalog").toAbsolute.toString(), driver="org.h2.Driver")

  //generate catalog tables in the beginning if not already existent
  val tableList = Await.result(db.run(MTable.getTables), 1.seconds).toList.map(x => x.name.name)
  Catalog().filterNot(mdd => tableList.contains(mdd._1)).foreach(mdd => {
    db.run(mdd._2.schema.create)
  })

  private val tables = TableQuery[TablesCatalog]
  private val indexes = TableQuery[IndexesCatalog]

  /**
   *
   * @param tablename
   */
  def createTable(tablename : TableName): Unit ={
    if(existsTable(tablename)){
      throw new TableExistingException()
    }

    val setup = DBIO.seq(
      tables.+=(tablename)
    )
    db.run(setup)
  }

  /**
   *
   * @param tablename
   * @param ifExists
   */
  def dropTable(tablename : TableName, ifExists : Boolean = false) = {
    if(!existsTable(tablename)){
      if(!ifExists){
        throw new TableNotExistingException()
      }
    } else {
      val query = tables.filter(_.tablename === tablename).delete
      val count = Await.result(db.run(query), 5.seconds)
    }
  }

  /**
   *
   * @param tablename
   * @return
   */
  def existsTable(tablename : TableName): Boolean ={
    val query = tables.filter(_.tablename === tablename).length.result
    val count = Await.result(db.run(query), 5.seconds)

    (count > 0)
  }

  /**
   *
   * @return
   */
  def listTables() : List[TableName] = {
    val query = tables.map(_.tablename).result
    Await.result(db.run(query), 5.seconds).toList
  }

  /**
   *
   * @param indexname
   * @return
   */
  def existsIndex(indexname : IndexName): Boolean = {
    val query = indexes.filter(_.indexname === indexname).length.result
    val count = Await.result(db.run(query), 5.seconds)

    (count > 0)
  }

  /**
   *
   * @param indexname
   * @param tablename
   * @param indexmeta
   */
  def createIndex(indexname : IndexName, tablename : TableName, indextypename : IndexTypeName, indexmeta : Serializable): Unit ={
    if(!existsTable(tablename)){
      throw new TableNotExistingException()
    }

    if(existsIndex(indexname)){
      throw new IndexExistingException()
    }

    val metaPath = config.indexPath + "/" + indexname + "/"
    val metaFilePath =  metaPath + "_adam_metadata"
    val oos = new ObjectOutputStream(new FileOutputStream(metaFilePath))
    oos.writeObject(indexmeta)
    oos.close

    val setup = DBIO.seq(
      indexes.+=((indexname, tablename, indextypename.toString, metaFilePath))
    )
    db.run(setup)
  }

  /**
   *
   * @param indexname
   * @return
   */
  def dropIndex(indexname : IndexName) : Unit = {
    if(!existsIndex(indexname)){
      throw new IndexNotExistingException()
    }

    val query = indexes.filter(_.indexname === indexname).delete
    Await.result(db.run(query), 5.seconds)
  }

  /**
   *
   * @param tablename
   * @return
   */
  def dropIndexesForTable(tablename: TableName) = {
    if(!existsTable(tablename)){
      throw new TableNotExistingException()
    }

    val existingIndexes = getIndexes(tablename)

    val query = indexes.filter(_.tablename === tablename).delete
    Await.result(db.run(query), 5.seconds)

    existingIndexes
  }

  /**
   *
   * @param tablename
   */
  def getIndexes(tablename : TableName): Seq[IndexName] = {
    val query = indexes.filter(_.tablename === tablename).map(_.indexname).result
    Await.result(db.run(query), 5.seconds).toList
  }

  /**
   *
   */
  def getIndexes(): Seq[IndexName] = {
    val query = indexes.map(_.indexname).result
    Await.result(db.run(query), 5.seconds).toList
  }


  /**
   *
   * @param indexname
   * @return
   */
  def getIndexMeta(indexname : IndexName) : Any = {
    val query = indexes.filter(_.indexname === indexname).map(_.indexmeta).result.head
    val path = Await.result(db.run(query), 5.seconds)
    val ois = new ObjectInputStream(new FileInputStream(path))
    ois.readObject()
  }

  /**
   *
   * @param indexname
   * @return
   */
  def getIndexTypeName(indexname : IndexName)  : IndexTypeName = {
    val query = indexes.filter(_.indexname === indexname).map(_.indextypename).result.head
    val result = Await.result(db.run(query), 5.seconds)

    IndexStructures.withName(result)
  }

  /**
   *
   * @param indexname
   * @return
   */
  def getIndexTableName(indexname : IndexName) : TableName = {
    val query = indexes.filter(_.indexname === indexname).map(_.tablename).result.head
    Await.result(db.run(query), 5.seconds)
  }

  /**
   *
   */
  def dropAllIndexes() : Unit = {
    val query = indexes.delete
    Await.result(db.run(query), 5.seconds)
  }
}
