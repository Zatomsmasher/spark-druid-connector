package org.rzlabs.druid.metadata

import org.apache.spark.sql.MyLogging
import org.apache.spark.sql.types.TimestampType
import org.apache.spark.util.MyThreadUtils
import org.codehaus.jackson.annotate.JsonIgnoreProperties
import org.rzlabs.druid._
import org.rzlabs.druid.client._
import org.fasterxml.jackson.databind.ObjectMapper._
import org.joda.time.Interval

import scala.collection.mutable.{Map => MMap}
import scala.util.Try

@JsonIgnoreProperties(ignoreUnknown = true)
case class DruidNode(name: String,
                     id: String,
                     address: String,
                     port: Int)

case class DruidClusterInfo(host: String,
                            curatorConnection: CuratorConnection,
                            serverStatus: ServerStatus,
                            druidDataSources: MMap[String, DruidDataSource])

trait DruidMetadataCache {

  def getDruidClusterInfo(druidRelationName: DruidRelationName,
                          options: DruidOptions): DruidClusterInfo

  def getDataSourceInfo(druidRelationName: DruidRelationName,
                        options: DruidOptions): DruidDataSource
}

trait DruidRelationInfoCache {

  self: DruidMetadataCache =>


  def buildColumnInfos(druidDataSource: DruidDataSource,
                       userSpecifiedColumnInfos: List[DruidRelationColumnInfo]
                       ): Map[String, DruidRelationColumn] = {
    val columns: Map[String, DruidColumn] = druidDataSource.columns

    def getDruidMetric(metricName: Option[String]): Option[DruidMetric] = {
      if (metricName.isDefined) {
        if (columns.contains(metricName.get) &&
            columns(metricName.get).isInstanceOf[DruidMetric]) {
          Some(columns(metricName.get).asInstanceOf[DruidMetric])
        } else None
      } else None
    }

    def isApproxMetric(druidColumn: DruidColumn) = {
      druidColumn.dataType == DruidDataType.HyperUnique ||
        druidColumn.dataType == DruidDataType.ThetaSketch
    }

    //val normalColumns: Map[String, DruidRelationColumn] = columns.map {
    //  case (columnName, druidColumn) if !isApproxMetric(druidColumn) =>
    //    val ci = userSpecifiedColumnInfos.find(_.column == columnName).getOrElse(null)
    //    val druidRelationColumn = if (ci != null) {
    //      val hllMetric = getDruidMetric(ci.hllMetric)
    //      val sketchMetric = getDruidMetric(ci.sketchMetric)
    //      DruidRelationColumn(columnName, Some(druidColumn), hllMetric, sketchMetric)
    //    } else {
    //      DruidRelationColumn(columnName, Some(druidColumn))
    //    }
    //    val cardinality: Option[Long] = if (druidColumn.isInstanceOf[DruidTimeDimension]) {
    //      Some(druidColumn.asInstanceOf[DruidTimeDimension].cardinality)
    //    } else if (druidColumn.isInstanceOf[DruidDimension]) {
    //      Some(druidColumn.asInstanceOf[DruidDimension].cardinality)
    //    } else if (druidColumn.isInstanceOf[DruidMetric]) {
    //      Some(druidColumn.asInstanceOf[DruidMetric].cardinality)
    //    } else None
    //    columnName -> druidRelationColumn.copy(cardinalityEstimate = cardinality)
    //  // Approx metric information should be carried by related origin column.
    //  case _ => (null, null)
    //}.filterNot(_._1 == null)

    val normalColumns: Map[String, DruidRelationColumn] = columns.map {
      case (columnName, druidColumn) =>
        val ci = userSpecifiedColumnInfos.find(_.column == columnName).getOrElse(null)
        val druidRelationColumn = if (ci != null) {
          val hllMetric = getDruidMetric(ci.hllMetric)
          val sketchMetric = getDruidMetric(ci.sketchMetric)
          DruidRelationColumn(columnName, Some(druidColumn), hllMetric, sketchMetric)
        } else {
          DruidRelationColumn(columnName, Some(druidColumn))
        }
        val cardinality: Option[Long] = if (druidColumn.isInstanceOf[DruidTimeDimension]) {
          Some(druidColumn.asInstanceOf[DruidTimeDimension].cardinality)
        } else if (druidColumn.isInstanceOf[DruidDimension]) {
          Some(druidColumn.asInstanceOf[DruidDimension].cardinality)
        } else if (druidColumn.isInstanceOf[DruidMetric]) {
          Some(druidColumn.asInstanceOf[DruidMetric].cardinality)
        } else None
        columnName -> druidRelationColumn.copy(cardinalityEstimate = cardinality)
    }

    // For the dimension user specified but not indexed in Druid datasource.
    val notIndexedColumns: Map[String, DruidRelationColumn] = userSpecifiedColumnInfos.collect {
      case ci if !columns.exists(_ == ci.column) => ci
    }.map {
      case ci: DruidRelationColumnInfo =>
        val hllMetric = getDruidMetric(ci.hllMetric)
        val sketchMetric = getDruidMetric(ci.sketchMetric)
        val cardinality = columns.find { _._1 ==
          hllMetric.getOrElse(sketchMetric.getOrElse(null))
        } match {
          case Some((_, druidColumn)) =>
            Some(druidColumn.asInstanceOf[DruidMetric].cardinality)
          case _ => None
        }
        ci.column -> DruidRelationColumn(ci.column, None, hllMetric, sketchMetric, cardinality)
    }.toMap

    normalColumns ++ notIndexedColumns
  }

  def druidRelation(dataSourceName: String,
                    timeDimensionCol: String,
                    userSpecifiedColumnInfos: List[DruidRelationColumnInfo],
                    options: DruidOptions): DruidRelationInfo = {

    val name = DruidRelationName(options.zkHost, dataSourceName)
    val druidDS = getDataSourceInfo(name, options)
    val columnInfos = buildColumnInfos(druidDS, userSpecifiedColumnInfos)
    val timeDimCol = druidDS.timeDimensionColName(timeDimensionCol)
    // Change the time dimension name "__time" to real time.
    val colInfos = columnInfos.map {
      case (colName, drCol) if colName == DruidDataSource.INNER_TIME_COLUMN_NAME =>
        (timeDimCol, drCol.copy(column = timeDimCol,
          druidColumn = Some(
            // The time dimension column's datatype should be StringType but not LongType
            drCol.druidColumn.get.asInstanceOf[DruidTimeDimension].copy(name = timeDimCol,
              dataType = DruidDataType.withName("STRING"))
          )))
      case other => other
    }
    DruidRelationInfo(name, timeDimCol, druidDS, colInfos, options)
  }
}

object DruidMetadataCache extends DruidMetadataCache with MyLogging with DruidRelationInfoCache {

  private[metadata] val cache: MMap[String, DruidClusterInfo] = MMap() // zkHost -> DruidClusterInfo
  private val curatorConnections: MMap[String, CuratorConnection] = MMap()
  private var brokerClient: DruidQueryServerClient = null
  val threadPool = MyThreadUtils.newDaemonCachedThreadPool("druidZkEventExec", 10)

  /**
   *
   * @param json
   */
  private def updateTimePeriod(json: String): Unit = {
    val root = jsonMapper.readTree(json)
    val action = Try(root.get("action").asText).recover({ case _ => null }).get  // "load" or "drop"
    val dataSource = Try(root.get("dataSource").asText).recover({ case _ => null }).get
    val interval = Try(root.get("interval").asText).recover({ case _ => null }).get
    if (action == null || dataSource == null || interval == null) return

    // Find datasource in `DruidClusterInfo` for each zkHost.
    logInfo(s"${action.toUpperCase} a segment of dataSource $dataSource with interval $interval.")
    cache.foreach {
      case (_, druidClusterInfo) => {
        val dDS: Option[DruidDataSource] = druidClusterInfo.druidDataSources.get(dataSource)
        if (dDS.isDefined) {  // find the dataSource the interval should be updated.
          val oldInterval: Interval = dDS.get.intervals(0)
          action.toUpperCase match {
            case "LOAD" =>
              // Don't call `timeBoundary` to update interval (cost to much).
              val newInterval = Utils.updateInterval(oldInterval, new Interval(interval), action)
              dDS.get.intervals = List(newInterval)
            case "DROP" =>
              // Call `timeBoundary` to update interval.
              dDS.get.intervals = List(brokerClient.timeBoundary(dataSource))
            case other => logWarning(s"Unkown segment action '$other'")
          }
          logInfo(s"The new interval of dataSource $dataSource is ${dDS.get.intervals(0)}")
        } // else do nothing
      }
    }
  }

  private def curatorConnection(host: String, options: DruidOptions): CuratorConnection = {
    curatorConnections.getOrElse(host, {
      val cc = new CuratorConnection(host, options, cache, threadPool, updateTimePeriod _)
      curatorConnections(host) = cc
      cc
    })
  }

  def getDruidClusterInfo(druidRelationName: DruidRelationName,
                          options: DruidOptions): DruidClusterInfo = {
    cache.synchronized {
      if (cache.contains(druidRelationName.zkHost)) {
        cache(druidRelationName.zkHost)
      } else {
        val zkHost = druidRelationName.zkHost
        val cc = curatorConnection(zkHost, options)
        val coordClient = new DruidCoordinatorClient(cc.getCoordinator)
        val serverStatus = coordClient.serverStatus
        val druidClusterInfo = new DruidClusterInfo(zkHost, cc, serverStatus,
          MMap[String, DruidDataSource]())
        cache(druidClusterInfo.host) = druidClusterInfo
        logInfo(s"Loading druid cluster info for $druidRelationName with zkHost $zkHost")
        druidClusterInfo
      }
    }
  }

  def getDataSourceInfo(druidRelationName: DruidRelationName,
                        options: DruidOptions): DruidDataSource = {
    val druidClusterInfo = getDruidClusterInfo(druidRelationName, options)
    val dataSourceName: String = druidRelationName.druidDataSource
    druidClusterInfo.synchronized {
      if (druidClusterInfo.druidDataSources.contains(dataSourceName)) {
        druidClusterInfo.druidDataSources(dataSourceName)
      } else {
        val broker: String = druidClusterInfo.curatorConnection.getBroker
        brokerClient = new DruidQueryServerClient(broker, false)
        val fullIndex = options.loadMetadataFromAllSegments
        val druidDS = brokerClient.metadata(dataSourceName, fullIndex)
          .copy(druidVersion = druidClusterInfo.serverStatus.version)
        druidClusterInfo.druidDataSources(dataSourceName) = druidDS
        logInfo(s"Druid datasource info for ${dataSourceName} is loaded.")
        druidDS
      }
    }
  }
}