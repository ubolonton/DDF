package io.ddf.spark.etl

import org.apache.spark.sql.DataFrame
import org.python.core._
import org.python.util.PythonInterpreter

import scala.collection.JavaConverters._
import scala.collection.JavaConversions.asScalaIterator
import scala.collection.JavaConversions.seqAsJavaList

import org.apache.spark.SparkContext.rddToPairRDDFunctions
import org.apache.spark.rdd.RDD
import org.apache.spark.storage.StorageLevel
import org.rosuda.REngine.REXP
import org.rosuda.REngine.REXPDouble
import org.rosuda.REngine.REXPInteger
import org.rosuda.REngine.REXPList
import org.rosuda.REngine.REXPLogical
import org.rosuda.REngine.REXPString
import org.rosuda.REngine.RList
import org.rosuda.REngine.Rserve.RConnection
import org.rosuda.REngine.Rserve.StartRserve

import _root_.io.ddf.DDF
import _root_.io.ddf.content.Schema
import _root_.io.ddf.content.Schema.Column
import _root_.io.ddf.etl.{TransformationHandler ⇒ CoreTransformationHandler}
import _root_.io.ddf.exception.DDFException
import _root_.io.ddf.spark.util.SparkUtils
import java.util.{Properties, ArrayList, List}

class TransformationHandler(mDDF: DDF) extends CoreTransformationHandler(mDDF) {


  override def flattenDDF(selectedColumns: Array[String]): DDF = {
    val df: DataFrame = mDDF.getRepresentationHandler.get(classOf[DataFrame]).asInstanceOf[DataFrame]
    val flattenedColumns: Array[String] = SparkUtils.flattenColumnNamesFromDataFrame(df, selectedColumns)

    val selectColumns: Array[String] = new Array[String](flattenedColumns.length)
    // update hive-invalid column names

    for(i <- flattenedColumns.indices) {
      selectColumns(i) = flattenedColumns(i).replaceAll("->", "_")
      if(selectColumns(i).charAt(0) == '_') {
        selectColumns(i) = selectColumns(i).substring(1)
      }
      selectColumns(i) = s"${flattenedColumns(i)} as ${selectColumns(i)}"
    }

    val selectClause = selectColumns.mkString(",")
    //val q = String.format("select %s from %s", selectClause, mDDF.getTableName)
    val q = s"select $selectClause from @this"

    //println("Query: \n" + q)

    val result = mDDF.sql2ddf(q)
    //println("Resulted DDF's columns: " + result.getColumnNames)
    result
  }

  override def flattenDDF(): DDF = {
    flattenDDF(null)
  }

  override def transformMapReduceNative(mapFuncDef: String, reduceFuncDef: String, mapsideCombine: Boolean = true): DDF = {

    // Prepare data as REXP objects
    val dfrdd = mDDF.getRepresentationHandler.get(classOf[RDD[_]], classOf[REXP]).asInstanceOf[RDD[REXP]]

    // 1. map!
    val rMapped = dfrdd.map {
      partdf ⇒
        try {
          TransformationHandler.preShuffleMapper(partdf, mapFuncDef, reduceFuncDef, mapsideCombine)
        } catch {
          case e: Exception ⇒ {

            e match {
              case aExc: DDFException ⇒ throw aExc
              case rserveExc: org.rosuda.REngine.Rserve.RserveException ⇒ {
                throw new DDFException(rserveExc.getMessage, null)
              }
              case _ ⇒ throw new DDFException(e.getMessage, null)
            }
          }
        }
    }

    // 2. extract map key and shuffle!
    val groupped = TransformationHandler.doShuffle(rMapped)

    // 3. reduce!
    val rReduced = groupped.mapPartitions {
      partdf ⇒
        try {
          TransformationHandler.postShufflePartitionMapper(partdf, reduceFuncDef)
        } catch {
          case e: Exception ⇒ {
            e match {
              case aExc: DDFException ⇒ throw aExc
              case rserveExc: org.rosuda.REngine.Rserve.RserveException ⇒ {
                throw new DDFException(rserveExc.getMessage, null)

              }
              case _ ⇒ throw new DDFException(e.getMessage, null)
            }
          }
        }
    }.filter {
      partdf ⇒
        // mapPartitions after groupByKey may cause some empty partitions,
        // which will result in empty data.frame
        val dflist = partdf.asList()
        dflist.size() > 0 && dflist.at(0).length() > 0
    }

    // convert R-processed DF partitions back to BigR DataFrame
    val columnArr = TransformationHandler.RDataFrameToColumnList(rReduced)


    val newSchema = new Schema(mDDF.getSchemaHandler.newTableName(), columnArr.toList);

    val manager = this.getManager
    val ddf = manager.newDDF(manager, rReduced, Array(classOf[RDD[_]],
      classOf[REXP]), manager.getEngineName, manager.getNamespace, null,
      newSchema)
    ddf
  }

  override def transformNativeRserve(transformExpression: String): DDF = {

    val dfrdd = mDDF.getRepresentationHandler.get(classOf[RDD[_]], classOf[REXP]).asInstanceOf[RDD[REXP]]

    // process each DF partition in R
    val rMapped = dfrdd.map {
      partdf ⇒
        try {
          // check if Rserve is running, if not: start it
          if (!StartRserve.checkLocalRserve()) throw new RuntimeException("Unable to start Rserve")
          // one connection for each compute job
          val rconn = new RConnection()

          // send the df.partition to R process environment
          val dfvarname = "df.partition"
          rconn.assign(dfvarname, partdf)

          val expr = String.format("%s <- transform(%s, %s)", dfvarname, dfvarname, transformExpression)

          // mLog.info(">>>>>>>>>>>>.expr=" + expr.toString())

          // compute!
          TransformationHandler.tryEval(rconn, expr, errMsgHeader = "failed to eval transform expression")

          // transfer data to JVM
          val partdfres = rconn.eval(dfvarname)

          // uncomment this to print whole content of the df.partition for debug
          // rconn.voidEval(String.format("print(%s)", dfvarname))
          rconn.close()

          partdfres
        } catch {
          case e: DDFException ⇒ {
            throw new DDFException("Unable to perform NativeRserve transformation", e)

          }
        }
    }

    // convert R-processed data partitions back to RDD[Array[Object]]
    val columnArr = TransformationHandler.RDataFrameToColumnList(rMapped)

    val newSchema = new Schema(mDDF.getSchemaHandler.newTableName(), columnArr.toList);

    val manager = this.getManager
    val ddf = manager.newDDF(manager, rMapped, Array(classOf[RDD[_]],
      classOf[REXP]), manager.getEngineName,  manager.getNamespace, null,
      newSchema)
    mLog.info(">>>>> adding ddf to manager: " + ddf.getName)
    ddf.getMetaDataHandler.copyFactor(this.getDDF)
    ddf
  }


  /**
   *
   * @param transformFunctions base64-encoded marshaled bytecode of the functions
   * @param destColumns names of the new columns
   * @param sourceColumns names of the source columns
   * @return
   */
  override def transformPython(transformFunctions: Array[String], destColumns: Array[String],
                               sourceColumns: Array[Array[String]]): DDF = {

    if (transformFunctions.length != destColumns.length || transformFunctions.length != sourceColumns.length ||
      transformFunctions.length <= 0) {
      throw new IllegalArgumentException("Lists of destination columns," +
        " source columns and transform functions must have the same length")
    }

    val dfrdd = mDDF.getRepresentationHandler.get(classOf[RDD[_]], classOf[PyObject]).asInstanceOf[RDD[PyObject]]

    // process each DF partition in Python
    val rMapped = dfrdd.map {
      partdf ⇒
        try {
          val props = new Properties()

          // prevent: console: Failed to install '': java.nio.charset.UnsupportedCharsetException: cp0.
          props.put("python.console.encoding", "UTF-8")
          //don't respect java accessibility, so that we can access protected members on subclasses
          props.put("python.security.respectJavaAccessibility", "false")
          // disable site
          props.put("python.import.site", "false")

          PythonInterpreter.initialize(System.getProperties, props, new Array[String](0))

          val interpreter = new PythonInterpreter()

          // when we do set() for a Array[String] variable, it will become array.array<> in jython,
          // hence casting is needed in the python code later.
          // Moreover loops will become awkward because __iter__ doesn't work with array.array
          interpreter.set("transform_codes", transformFunctions)
          interpreter.set("src_cols", sourceColumns)
          interpreter.set("dest_cols", destColumns)
          interpreter.set("df_part", partdf)

          interpreter.exec(
            """
              |import marshal, types, base64
              |
              |def load_code(code_string):
              |  code = marshal.loads(base64.urlsafe_b64decode(code_string))
              |  return types.FunctionType(code, globals(), "transformation_function")
              |
              |funcs = [load_code(str(x)) for x in transform_codes]
              |
              |for f, dest, src in zip(funcs, [x for x in dest_cols], [y for y in src_cols]):
              |  data_src = tuple(df_part[str(c)] for c in src)
              |  df_part[str(dest)] = [f(*args) for args in zip(*data_src)]
              |
            """.stripMargin
          )
          val obj = interpreter.get("df_part")

          interpreter.cleanup()
          interpreter.close()
          obj
        } catch {
          case e: PyException ⇒ throw new DDFException("Unable to perform Python transformation", e)
        }
    }

    // convert Python-processed data partitions back to RDD[Array[Object]]
    val columnArr = TransformationHandler.PyObjectToColumnList(rMapped)

    val newSchema = new Schema(mDDF.getSchemaHandler.newTableName(), columnArr.toList)

    val manager = this.getManager
    val ddf = manager.newDDF(manager, rMapped, Array(classOf[RDD[_]], classOf[PyObject]),
      manager.getEngineName, manager.getNamespace, null, newSchema)
    mLog.info(">>>>> adding ddf to manager: " + ddf.getName)
    ddf.getMetaDataHandler.copyFactor(this.getDDF)
    ddf
  }
}

object TransformationHandler {

  /**
   * Eval the expr in rconn, if succeeds return null (like rconn.voidEval),
   * if fails raise AdataoException with captured R error message.
   * See: http://rforge.net/Rserve/faq.html#errors
   */
  def tryEval(rconn: RConnection, expr: String, errMsgHeader: String) {
    rconn.assign(".tmp.", expr)
    val r = rconn.eval("r <- try(eval(parse(text=.tmp.)), silent=TRUE); if (inherits(r, 'try-error')) r else NULL")
    if (r.inherits("try-error")) throw new DDFException(errMsgHeader + ": " + r.asString())
  }

  /**
   * eval the R expr and return all captured output
   */
  def evalCaptureOutput(rconn: RConnection, expr: String): String = {
    rconn.eval("paste(capture.output(print(" + expr + ")), collapse='\\n')").asString()
  }

  def RDataFrameToColumnList(rdd: RDD[REXP]): Array[Column] = {
    val firstdf = rdd.first()
    val names = firstdf.getAttribute("names").asStrings()
    val columns = new Array[Column](firstdf.length)
    for (j ← 0 until firstdf.length()) {
      val ddfType = firstdf.asList().at(j) match {
        case v: REXPDouble ⇒ "DOUBLE"
        case v: REXPInteger ⇒ "INT"
        case v: REXPString ⇒ "STRING"
        case _ ⇒ throw new DDFException("Only support atomic vectors of type int|double|string!")
      }
      columns(j) = new Column(names(j), ddfType)
    }
    columns
  }

  /**
   * Lots of type casting
   * @param rdd the RDD
   * @return an array of Column(s)
   */
  def PyObjectToColumnList(rdd: RDD[PyObject]): Array[Column] = {
    val firstdf = rdd.first()

    if (!firstdf.isMappingType) {
      throw new DDFException("Expect a dict of lists")
    }

    val dct = firstdf.asInstanceOf[PyDictionary]
    val columns = new Array[Column](dct.size())
    val keys = dct.keys()
    for (i <- 0 until keys.size()) {
      val k = keys.get(i).asInstanceOf[String]

      val ddfType = dct.get(k).asInstanceOf[PyList].get(0) match {
        case v: Integer => "INT"
        case v: java.lang.Double => "DOUBLE"
        case v: java.lang.Float => "DOUBLE"
        case v: java.lang.String => "STRING"
        case x => throw new DDFException("Only support atomic vectors of type int|float|string")
      }
      columns(i) = new Column(k, ddfType)
    }
    columns
  }

  /**
   * Perform map and mapsideCombine phase
   */
  def preShuffleMapper(partdf: REXP, mapFuncDef: String, reduceFuncDef: String, mapsideCombine: Boolean): REXP = {
    // check if Rserve is running, if not: start it
    if (!StartRserve.checkLocalRserve()) throw new RuntimeException("Unable to start Rserve")
    // one connection for each compute job
    val rconn = new RConnection()

    // send the df.partition to R process environment
    rconn.assign("df.partition", partdf)
    rconn.assign("mapside.combine", new REXPLogical(mapsideCombine))

    TransformationHandler.tryEval(rconn, "map.func <- " + mapFuncDef,
      errMsgHeader = "fail to eval map.func definition")
    TransformationHandler.tryEval(rconn, "combine.func <- " + reduceFuncDef,
      errMsgHeader = "fail to eval combine.func definition")

    // pre-amble to define internal functions
    // copied from: https://github.com/adatao/RClient/blob/master/io.pa/R/mapreduce.R
    // tests: https://github.com/adatao/RClient/blob/mapreduce/io.pa/inst/tests/test-mapreduce.r#L106
    // should consider some packaging to synchroncize code
    rconn.voidEval(
      """
        |#' Emit keys and values for map/reduce.
        |keyval <- function(key, val) {
        |  if (! is.atomic(key))
        |    stop(paste("keyval: key argument must be an atomic vector: ", paste(key, collapse=" ")))
        |  if (! is.null(dim(key)))
        |    stop(paste("keyval: key argument must be one-dimensional: dim(key) = ",
        |               paste(dim(key), collapse=" ")))
        |  nkey <- length(key)
        |  nval <- if (! is.null(nrow(val))) nrow(val) else length(val)
        |  if (nkey != nval)
        |    stop(sprintf("keyval: key and val arguments must match in length/nrow: %s != %s", nkey, nval))
        |  kv <- list(key=key, val=val);
        |  attr(kv, "adatao-2d-kv-pair") <- T;
        |  kv
        |}
        |
        |#' Emit a single key and value pair for map/reduce.
        |keyval.row <- function(key, val) {
        |  if (! is.null(dim(key)))
        |    stop(paste("keyval: key argument must be a scala value, not n-dimensional: dim(key) = ",
        |               paste(dim(key), collapse=" ")))
        |  if (length(key) != 1)
        |    stop(paste("keyval.row: key argument must be a scalar value: ", paste(key, collapse=" ")))
        |  if (! is.null(dim(val)))
        |    stop(paste("keyval: val argument must be one-: dim(val) = ",
        |               paste(dim(val), collapse=" ")))
        |  kv <- list(key=key, val=val);
        |  attr(kv, "adatao-1d-kv-pair") <- T;
        |  kv
        |}
        |
        |#' does the kv pair have a adatao-defined attr?
        |is.adatao.kv <- function(kv) { (! is.null(attr(kv, "adatao-1d-kv-pair"))) | (! is.null(attr(kv, "adatao-2d-kv-pair"))) }
        |
        |#' should this be splitted?
        |is.adatao.1d.kv <- function(kv) { ! is.null(attr(kv, "adatao-1d-kv-pair")) }
        |
        |do.pre.shuffle <- function(partition, map.func, combine.func, mapside.combine = T, debug = F) {
        |  print("==== map phase begins ...")
        |  kv <- map.func(partition)
        |  if (debug) { print("kv = "); str(kv) }
        |
        |  if (is.adatao.1d.kv(kv)) {
        |    # list of a single keyval object, with the serialized
        |    return(list(keyval.row(kv$key, serialize(kv$val, NULL))))
        |  } else if (!is.adatao.kv(kv)) {
        |    print(paste("skipping non-adatao kv = ", kv))
        |  }
        |
        |  val.bykey <- split(kv$val, f=kv$key)
        |  if (debug) { print("val.bykey ="); str(val.bykey) }
        |  keys <- names(val.bykey)
        |
        |  result <- if (mapside.combine) {
        |    combine.result <- vector('list', length(keys))
        |    for (i in 1:length(val.bykey)) {
        |      kv <- combine.func(keys[[i]], val.bykey[[i]])
        |      if (debug) { print("combined kv = "); str(kv) }
        |      combine.result[[i]] <- keyval.row(kv$key, serialize(kv$val, NULL))
        |    }
        |    # if (debug) print(combine.result)
        |    combine.result
        |  } else {
        |    kvlist.byrow <- vector('list', length(kv$key))
        |    z <- 1
        |    for (i in 1:length(keys)) {
        |      k <- keys[[i]]
        |      vv <- val.bykey[[i]]
        |      if (is.atomic(vv)) {
        |        for (j in 1:length(vv)) {
        |          kvlist.byrow[[z]] <- keyval.row(k, serialize(vv[[j]], NULL))
        |          z <- z + 1
        |        }
        |      } else {
        |        for (j in 1:nrow(vv)) {
        |          kvlist.byrow[[z]] <- keyval.row(k, serialize(vv[j, ], NULL))
        |          z <- z + 1
        |        }
        |      }
        |    }
        |    # if (debug) print(kvlist.byrow)
        |    kvlist.byrow
        |  }
        |  print("==== map phase completed")
        |  # if (debug) { print("kvlist.byrow = "); str(kvlist.byrow) }
        |  result
        |}
      """.stripMargin)

    // map!
    TransformationHandler.tryEval(rconn, "pre.shuffle.result <- do.pre.shuffle(df.partition, map.func, combine.func, mapside.combine, debug=T)",
      errMsgHeader = "fail to apply map.func to data partition")

    // transfer pre-shuffle result into JVM
    val result = rconn.eval("pre.shuffle.result")

    // we will another RConnection because we will now shuffle data
    rconn.close()

    result
  }

  /**
   * By now, whether mapsideCombine is true or false,
   * we both have each partition as a list of list(key=..., val=...)
   */
  //  def doShuffle(rMapped: RDD[REXP]): RDD[(String, Iterable[REXP])] = {
  def doShuffle(rMapped: RDD[REXP]): RDD[(String, Iterable[REXP])] = {
    val groupped = rMapped.flatMap {
      rexp ⇒
        rexp.asList().iterator.map {
          kv ⇒
            val kvl = kv.asInstanceOf[REXP].asList

            val (k, v) = (kvl.at("key").asString(), kvl.at("val"))
            (k, v)
        }
    }.groupByKey()
    //TODO
    groupped
  }

  /**
   * serialize data to R, perform reduce,
   * then assemble each resulting partition as a data.frame of REXP in Java
   */
  def postShufflePartitionMapper(input: Iterator[(String, Iterable[REXP])], reduceFuncDef: String): Iterator[REXP] = {
    // check if Rserve is running, if not: start it
    if (!StartRserve.checkLocalRserve()) throw new RuntimeException("Unable to start Rserve")
    val rconn = new RConnection()

    // pre-amble
    // copied from: https://github.com/adatao/RClient/blob/master/io.pa/R/mapreduce.R
    // tests: https://github.com/adatao/RClient/blob/mapreduce/io.pa/inst/tests/test-mapreduce.r#L238
    // should consider some packaging to synchronize code
    rconn.voidEval(
      """
        |#' Emit keys and values for map/reduce.
        |keyval <- function(key, val) {
        |  if (! is.atomic(key))
        |    stop(paste("keyval: key argument must be an atomic vector: ", paste(key, collapse=" ")))
        |  if (! is.null(dim(key)))
        |    stop(paste("keyval: key argument must be one-dimensional: dim(key) = ",
        |               paste(dim(key), collapse=" ")))
        |  nkey <- length(key)
        |  nval <- if (! is.null(nrow(val))) nrow(val) else length(val)
        |  if (nkey != nval)
        |    stop(sprintf("keyval: key and val arguments must match in length/nrow: %s != %s", nkey, nval))
        |  kv <- list(key=key, val=val);
        |  attr(kv, "adatao-2d-kv-pair") <- T;
        |  kv
        |}
        |
        |#' Emit a single key and value pair for map/reduce.
        |keyval.row <- function(key, val) {
        |  if (! is.null(dim(key)))
        |    stop(paste("keyval: key argument must be a scala value, not n-dimensional: dim(key) = ",
        |               paste(dim(key), collapse=" ")))
        |  if (length(key) != 1)
        |    stop(paste("keyval.row: key argument must be a scalar value: ", paste(key, collapse=" ")))
        |  if (! is.null(dim(val)))
        |    stop(paste("keyval: val argument must be one-: dim(val) = ",
        |               paste(dim(val), collapse=" ")))
        |  kv <- list(key=key, val=val);
        |  attr(kv, "adatao-1d-kv-pair") <- T;
        |  kv
        |}
        |
        |#' does the kv pair have a adatao-defined attr?
        |is.adatao.kv <- function(kv) { (! is.null(attr(kv, "adatao-1d-kv-pair"))) | (! is.null(attr(kv, "adatao-2d-kv-pair"))) }
        |
        |#' should this be splitted?
        |is.adatao.1d.kv <- function(kv) { ! is.null(attr(kv, "adatao-1d-kv-pair")) }
        |
        |# flatten the reduced kv pair.
        |flatten.kvv <- function(rkv) {
        |  if (length(rkv$val) > 1) {
        |    row <- vector('list', length(rkv$val) + 1)
        |    row[1] <- rkv$key
        |    row[2:(length(rkv$val)+1)] <- rkv$val
        |    names(row) <- c("key", names(rkv$val))
        |    row
        |  } else {
        |    rkv
        |  }
        |}
        |
        |#' bind together list of values from the same keys as rows of a data.frame
        |rbind.vv <- function(vvlist) {
        |  df <- do.call(rbind.data.frame, vvlist)
        |  if (length(vvlist) > 0) {
        |    head <- vvlist[[1]]
        |    if ( is.null(names(head)) ) {
        |      if (length(head) == 1) {
        |        names(df) <- c("val")
        |      } else {
        |        names(df) <- Map(function(x){ paste("val", x, sep="") }, 1:length(head))
        |      }
        |    }
        |  }
        |  df
        |}
        |
        |handle.reduced.kv <- function(rkv) {
        |  if (is.adatao.1d.kv(rkv)) {
        |    row <- flatten.kvv(rkv)
        |    row
        |  } else if (is.adatao.kv(rkv)) {
        |    df <- rkv$val
        |    df$key <- rkv$key
        |    df
        |  } else {
        |    print("skipping not-supported reduce.func output = "); str(rkv)
        |    NULL
        |  }
        |}
      """.stripMargin)

    TransformationHandler.tryEval(rconn, "reduce.func <- " + reduceFuncDef,
      errMsgHeader = "fail to eval reduce.func definition")

    rconn.voidEval("reductions <- list()")
    rconn.voidEval("options(stringsAsFactors = F)")

    // we do this in a loop because each of the seqv could potentially be very large
    input.zipWithIndex.foreach {
      case ((k: String, seqv: Seq[_]), i: Int) ⇒

        // send data to R to compute reductions
        rconn.assign("idx", new REXPInteger(i))
        rconn.assign("reduce.key", k)
        rconn.assign("reduce.serialized.vvlist", new REXPList(new RList(seqv)))

        // print to Rserve log
        rconn.voidEval("print(paste('====== processing key = ', reduce.key))")

        TransformationHandler.tryEval(rconn, "reduce.vvlist <- lapply(reduce.serialized.vvlist, unserialize)",
          errMsgHeader = "fail to unserialize shuffled values for key = " + k)

        TransformationHandler.tryEval(rconn, "reduce.vv <- rbind.vv(reduce.vvlist)",
          errMsgHeader = "fail to merge (using rbind.vv) shuffled values for key = " + k)

        // reduce!
        TransformationHandler.tryEval(rconn, "reduced.kv <- reduce.func(reduce.key, reduce.vv)",
          errMsgHeader = "fail to apply reduce func to data partition")

        // flatten the nested val list if needed
        TransformationHandler.tryEval(rconn, "reduced <- handle.reduced.kv(reduced.kv)",
          errMsgHeader = "malformed reduce.func output, please run mapreduce.local to test your reduce.func")

        // assign reduced item to reductions list
        rconn.voidEval("if (!is.null(reduced)) { reductions[[idx+1]] <- reduced } ")
    }

    // bind the reduced rows together, it contains rows of the resulting BigDataFrame
    TransformationHandler.tryEval(rconn, "reduced.partition <- do.call(rbind.data.frame, reductions)",
      errMsgHeader = "fail to use rbind.data.frame on reductions list, reduce.func cannot be combined as a BigDataFrame")

    // remove weird row names
    rconn.voidEval("rownames(reduced.partition) <- NULL")

    // transfer reduced data back to JVM
    val result = rconn.eval("reduced.partition")

    // print to Rserve log
    rconn.voidEval("print('==== reduce phase completed')")

    // done R computation for this partition
    rconn.close()

    // wrap it on a Iterator to satisfy mapPartitions
    Iterator.single(result)
  }
}
