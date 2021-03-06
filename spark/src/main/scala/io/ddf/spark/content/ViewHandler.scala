/**
 *
 */
package io.ddf.spark.content

import io.ddf.DDF
import io.ddf.content.IHandleViews
import scala.collection.JavaConverters._
import io.ddf.content.Schema
import io.ddf.spark.{SparkDDFManager, SparkDDF}
import org.apache.spark.sql.{DataFrame, Row}
import org.apache.spark.rdd.RDD

/**
 * RDD-based ViewHandler
 *
 *
 */
class ViewHandler(mDDF: DDF) extends io.ddf.content.ViewHandler(mDDF) with IHandleViews {

  object ViewFormat extends Enumeration {
    type ViewFormat = Value
    val DEFAULT, ARRAY_OBJECT, ARRAY_DOUBLE, TABLE_PARTITION, LABELED_POINT, LABELED_POINTS = Value
  }

  import ViewFormat._

  /**
   * Same as {@link #get(int[], int)}, but accepts a scala.Enumeration for format instead.
   *
   * @param columns
   * @param format
   * A scala.Enumeration that will be converted to an integer by calling
   * formatEnum.toString()
   * @return
   */
  def get(columns: Array[Int], format: ViewFormat): DDF = {
    format match {
      case ViewFormat.DEFAULT => ViewHandler.getDefault(columns, mDDF)
      case ViewFormat.ARRAY_OBJECT => ViewHandler.getArrayObject(columns, mDDF)
      case ViewFormat.ARRAY_DOUBLE => ViewHandler.getArrayDouble(columns, mDDF)
      case ViewFormat.TABLE_PARTITION => ViewHandler.getTablePartition(columns, mDDF)
      case ViewFormat.LABELED_POINT => ViewHandler.getLabeledPoint(columns, mDDF)
      case ViewFormat.LABELED_POINTS => ViewHandler.getLabeledPoints(columns, mDDF)
      case _ => {}
    }
    null
  }

  protected def getImpl(columns: Array[Int], format: String): DDF = {
    this.get(columns, ViewFormat.withName(format))
  }

  val MAX_SAMPLE_SIZE = 1000000;

  override def getRandomSample(numSamples: Int, withReplacement: Boolean, seed: Int): java.util.List[Array[Object]] = {
    if (numSamples > MAX_SAMPLE_SIZE) {
      throw new IllegalArgumentException("Number of samples is currently limited to %d".format(MAX_SAMPLE_SIZE))
    } else {
      if(mDDF.getRepresentationHandler.has(classOf[RDD[_]], classOf[Array[Object]])) {
        val rdd = mDDF.asInstanceOf[SparkDDF].getRDD(classOf[Array[Object]])
        val sampleData = rdd.takeSample(withReplacement, numSamples, seed).toList.asJava
        sampleData
      } else {
        val rdd = mDDF.asInstanceOf[SparkDDF].getRDD(classOf[Row])
        rdd.takeSample(withReplacement, numSamples, seed).map {
          row => {
            row.toSeq.toArray.asInstanceOf[Array[Object]]
          }
        }.toList.asJava
      }
    }
  }

  override def getRandomSample(fraction: Double, withReplacement: Boolean, seed: Int): DDF = {
    if (fraction > 1 || fraction < 0) {
      throw new IllegalArgumentException("Sampling fraction must be from 0 to 1")
    } else {
      val df: DataFrame = mDDF.getRepresentationHandler.get(classOf[DataFrame]).asInstanceOf[DataFrame]
      val sample_df = df.sample(withReplacement, fraction, seed)
      val schema = SchemaHandler.getSchemaFromDataFrame(sample_df)
      schema.setTableName(mDDF.getSchemaHandler.newTableName())
      val manager = this.getManager
      val sampleDDF = manager.newDDF(manager, sample_df, Array
        (classOf[DataFrame]), manager.getEngineName, manager.getNamespace,
        null, schema)
      mLog.info(">>>>>>> adding ddf to DDFManager " + sampleDDF.getName)
      sampleDDF.getMetaDataHandler.copyFactor(this.getDDF)
      sampleDDF
    }
  }
}

object ViewHandler {
  def getDefault(cols: Array[Int], theDDF: DDF): DDF = {

    null
  }

  def getArrayObject(cols: Array[Int], theDDF: DDF): DDF = {

    null
  }

  def getArrayDouble(cols: Array[Int], theDDF: DDF): DDF = {

    null
  }

  def getTablePartition(cols: Array[Int], theDDF: DDF): DDF = {

    null
  }

  def getLabeledPoint(cols: Array[Int], theDDF: DDF): DDF = {

    null
  }

  def getLabeledPoints(cols: Array[Int], theDDF: DDF): DDF = {

    null
  }
}
