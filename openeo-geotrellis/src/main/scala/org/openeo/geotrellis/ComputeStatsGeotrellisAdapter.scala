package org.openeo.geotrellis

import java.io.File
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util

import be.vito.eodata.processing.MaskedStatisticsProcessor.StatsMeanResult
import geotrellis.layer._
import geotrellis.raster.histogram.Histogram
import geotrellis.raster.summary.Statistics
import geotrellis.spark._
import org.apache.spark.SparkContext
import org.apache.spark.storage.StorageLevel.MEMORY_AND_DISK_SER
import org.openeo.geotrellis.aggregate_polygon.intern._
import org.openeo.geotrellis.aggregate_polygon.{AggregatePolygonProcess, intern}
import org.slf4j.{Logger, LoggerFactory}

import scala.collection.JavaConverters._


object ComputeStatsGeotrellisAdapter {
  private type JMap[K, V] = java.util.Map[K, V]
  private type JList[T] = java.util.List[T]

  private type MultibandMeans = Seq[StatsMeanResult]

  private implicit val logger: Logger = LoggerFactory.getLogger(classOf[ComputeStatsGeotrellisAdapter])

  // OpenEO doesn't return physical values
  private val noScaling = 1.0
  private val noOffset = 0.0

  private object Sigma0Band extends Enumeration {
    val VH, VV, Angle = Value
  }

  private def singleBand(bandIndex: Int): MultibandTileLayerRDD[SpaceTimeKey] => TileLayerRDD[SpaceTimeKey] =
    multiBandRdd => {
      val valueRdd = multiBandRdd.mapValues(multiBandTile => multiBandTile.band(bandIndex))
      ContextRDD(valueRdd, multiBandRdd.metadata)
    }


  private def toMap(histogram: Histogram[Double]): JMap[Double, Long] = {
    val buckets: JMap[Double, Long] = new util.HashMap[Double, Long]
    histogram.foreach { case (value, count) => buckets.put(value, count) }

    buckets
  }

  private def isoFormat(timestamp: ZonedDateTime): String = timestamp format DateTimeFormatter.ISO_DATE_TIME
}

class ComputeStatsGeotrellisAdapter(zookeepers: String, accumuloInstanceName: String) {
  import ComputeStatsGeotrellisAdapter._

  private val unusedCancellationContext = new CancellationContext(null, null)


  def compute_average_timeseries_from_datacube(datacube: MultibandTileLayerRDD[SpaceTimeKey], polygons: ProjectedPolygons, from_date: String, to_date: String, band_index: Int): JMap[String, JList[JList[Double]]] = {
    val computeStatsGeotrellis = new AggregatePolygonProcess()

    val startDate: ZonedDateTime = ZonedDateTime.parse(from_date)
    val endDate: ZonedDateTime = ZonedDateTime.parse(to_date)
    val statisticsCollector = new MultibandStatisticsCollector

    computeStatsGeotrellis.computeAverageTimeSeries(datacube.persist(MEMORY_AND_DISK_SER), polygons.polygons, polygons.crs, startDate, endDate, statisticsCollector, unusedCancellationContext, sc)

    statisticsCollector.results
  }

  /**
   * Writes means to an UTF-8 encoded JSON file.
   */
  def compute_average_timeseries_from_datacube(datacube: MultibandTileLayerRDD[SpaceTimeKey], polygons: ProjectedPolygons, from_date: String, to_date: String, band_index: Int, output_file: String): Unit = {
    val computeStatsGeotrellis = new AggregatePolygonProcess()

    val startDate: ZonedDateTime = ZonedDateTime.parse(from_date)
    val endDate: ZonedDateTime = ZonedDateTime.parse(to_date)

    val statisticsWriter = new MultibandStatisticsWriter(new File(output_file))

    try
      computeStatsGeotrellis.computeAverageTimeSeries(datacube.persist(MEMORY_AND_DISK_SER), polygons.polygons, polygons.crs, startDate, endDate, statisticsWriter, unusedCancellationContext, sc)
    finally
      statisticsWriter.close()
  }

  def compute_histograms_time_series_from_datacube(datacube: MultibandTileLayerRDD[SpaceTimeKey], polygons: ProjectedPolygons,
                                                   from_date: String, to_date: String, band_index: Int
                                                  ): JMap[String, JList[JList[JMap[Double, Long]]]] = { // date -> polygon -> band -> value/count
    val histogramsCollector = new MultibandHistogramsCollector
    _compute_histograms_time_series_from_datacube(datacube, polygons, from_date, to_date, band_index, histogramsCollector)
    histogramsCollector.results
  }

  def compute_median_time_series_from_datacube(datacube: MultibandTileLayerRDD[SpaceTimeKey], polygons: ProjectedPolygons,
                                               from_date: String, to_date: String, band_index: Int
                                              ): JMap[String, JList[JList[Double]]] = {
    val mediansCollector = new MultibandMediansCollector
    _compute_histograms_time_series_from_datacube(datacube, polygons, from_date, to_date, band_index, mediansCollector)
    mediansCollector.results
  }

  def compute_sd_time_series_from_datacube(datacube: MultibandTileLayerRDD[SpaceTimeKey], polygons: ProjectedPolygons,
                                           from_date: String, to_date: String, band_index: Int
                                          ): JMap[String, JList[JList[Double]]] = { // date -> polygon -> value/count
    val stdDevCollector = new MultibandStdDevCollector
    _compute_histograms_time_series_from_datacube(datacube, polygons, from_date, to_date, band_index, stdDevCollector)
    stdDevCollector.results
  }

  private def _compute_histograms_time_series_from_datacube(datacube: MultibandTileLayerRDD[SpaceTimeKey], polygons: ProjectedPolygons, from_date: String, to_date: String, band_index: Int, histogramsCollector: StatisticsCallback[_ >: Seq[Histogram[Double]]]): Unit = {
    val startDate: ZonedDateTime = ZonedDateTime.parse(from_date)
    val endDate: ZonedDateTime = ZonedDateTime.parse(to_date)
    intern.computeHistogramTimeSeries(datacube, polygons.polygons, polygons.crs, startDate, endDate, histogramsCollector, unusedCancellationContext, sc)
  }



  private def sc: SparkContext = SparkContext.getOrCreate()


  private class StatisticsCollector extends StatisticsCallback[StatsMeanResult] {
    import java.util._

    val results: JMap[String, JList[Double]] =
      Collections.synchronizedMap(new util.HashMap[String, JList[Double]])

    override def onComputed(date: ZonedDateTime, results: Seq[StatsMeanResult]): Unit = {
      val means = results.map(_.getAverage)

      this.results.put(isoFormat(date), means.asJava)
    }

    override def onCompleted(): Unit = ()
  }

  private class MultibandStatisticsCollector extends StatisticsCallback[Seq[StatsMeanResult]] {
    import java.util._

    val results: JMap[String, JList[JList[Double]]] =
      Collections.synchronizedMap(new util.HashMap[String, JList[JList[Double]]])

    override def onComputed(date: ZonedDateTime, results: Seq[Seq[StatsMeanResult]]): Unit = {
      val means = results.map(_.map(_.getAverage))

      this.results.put(isoFormat(date), means.map(_.asJava).asJava)
    }

    override def onCompleted(): Unit = ()
  }

  private class MultibandStatisticsWriter(outputFile: File) extends StatisticsCallback[MultibandMeans] with AutoCloseable {
    import com.fasterxml.jackson.core.JsonEncoding.UTF8
    import com.fasterxml.jackson.databind.ObjectMapper

    private val jsonGenerator = (new ObjectMapper).getFactory.createGenerator(outputFile, UTF8)

    jsonGenerator.synchronized {
      jsonGenerator.writeStartObject()
      jsonGenerator.flush()
    }

    override def onComputed(date: ZonedDateTime, polygonalMultibandMeans: Seq[MultibandMeans]): Unit =
      jsonGenerator.synchronized {
        jsonGenerator.writeArrayFieldStart(isoFormat(date))

        for (polygon <- polygonalMultibandMeans) {
          jsonGenerator.writeStartArray()

          for (bandMean <- polygon)
            if (bandMean.getAverage.isNaN) jsonGenerator.writeNull()
            else jsonGenerator.writeNumber(bandMean.getAverage)

          jsonGenerator.writeEndArray()
        }

        jsonGenerator.writeEndArray()
        jsonGenerator.flush()
      }

    override def onCompleted(): Unit =
      jsonGenerator.synchronized {
        jsonGenerator.writeEndObject()
        jsonGenerator.flush()
      }

    override def close(): Unit = jsonGenerator.close()
  }

  private class HistogramsCollector extends StatisticsCallback[Histogram[Double]] {
    import java.util._

    val results: JMap[String, JList[JMap[Double, Long]]] =
      Collections.synchronizedMap(new util.HashMap[String, JList[JMap[Double, Long]]])

    override def onComputed(date: ZonedDateTime, results: Seq[Histogram[Double]]): Unit = {
      val polygonalHistograms = results map toMap

      this.results.put(isoFormat(date), polygonalHistograms.asJava)
    }

    override def onCompleted(): Unit = ()
  }

  private class MultibandHistogramsCollector extends StatisticsCallback[Seq[Histogram[Double]]] {
    import java.util._

    val results: JMap[String, JList[JList[JMap[Double, Long]]]] =
      Collections.synchronizedMap(new util.HashMap[String, JList[JList[JMap[Double, Long]]]])

    override def onComputed(date: ZonedDateTime, results: Seq[Seq[Histogram[Double]]]): Unit = {
      val polygonalHistograms: Seq[JList[JMap[Double, Long]]] = results.map( _.map(toMap).asJava)
      this.results.put(isoFormat(date), polygonalHistograms.asJava)
    }

    override def onCompleted(): Unit = ()
  }

  private class MultibandMediansCollector extends StatisticsCallback[intern.MultibandHistogram[Double]] {
    import java.util._

    val results: JMap[String, JList[JList[Double]]] =
      Collections.synchronizedMap(new util.HashMap[String, JList[JList[Double]]])

    override def onComputed(date: ZonedDateTime, multibandHistograms: Seq[MultibandHistogram[Double]]): Unit = {
      val polygonalMultibandMedians: Seq[JList[Double]] = for {
        multibandHistogram <- multibandHistograms
        multibandMedian = multibandHistogram.map(_.median().getOrElse(Double.NaN))
      } yield multibandMedian.asJava

      this.results.put(isoFormat(date), polygonalMultibandMedians.asJava)
    }

    override def onCompleted(): Unit = ()
  }

  private class MultibandStdDevCollector extends StatisticsCallback[Seq[Histogram[Double]]] {
    import java.util._

    val results: JMap[String, JList[JList[Double]]] =
      Collections.synchronizedMap(new util.HashMap[String, JList[JList[Double]]])

    override def onComputed(date: ZonedDateTime, results: Seq[Seq[Histogram[Double]]]): Unit = {
      val polygonalStdDevs: Seq[JList[Double]] = results.map( _.map(_.statistics().getOrElse(Statistics.EMPTYDouble()).stddev).asJava)
      this.results.put(isoFormat(date), polygonalStdDevs.asJava)
    }

    override def onCompleted(): Unit = ()
  }
}
