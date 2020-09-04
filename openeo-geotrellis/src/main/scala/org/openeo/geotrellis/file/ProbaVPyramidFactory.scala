package org.openeo.geotrellis.file

import java.lang.Math.max
import java.net.URI
import java.time.ZonedDateTime
import java.util

import be.vito.eodata.extracttimeseries.geotrellis.ProbavFileLayerProvider
import cats.data.NonEmptyList
import geotrellis.layer._
import geotrellis.proj4.{CRS, WebMercator}
import geotrellis.raster.geotiff.GeoTiffRasterSource
import geotrellis.raster.io.geotiff.tags.TiffTags
import geotrellis.raster.{CellType, MultibandTile, ShortUserDefinedNoDataCellType, Tile}
import geotrellis.spark.partition.SpacePartitioner
import geotrellis.spark.pyramid.Pyramid
import geotrellis.spark.store.hadoop.geotiff.{GeoTiffMetadata, InMemoryGeoTiffAttributeStore}
import geotrellis.spark.{ContextRDD, MultibandTileLayerRDD}
import geotrellis.store.hadoop.util.{HdfsRangeReader, HdfsUtils}
import geotrellis.vector.{Extent, MultiPolygon, ProjectedExtent}
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD
import org.openeo.geotrellis.OpenEOProcesses

import scala.collection.JavaConverters._
import scala.collection.Map

object ProbaVPyramidFactory {
  private val maxZoom = 9

  object Band extends Enumeration {
    // Jesus Christ almighty
    private[file] case class Val(fileMarker: String) extends super.Val
    implicit def valueToVal(x: Value): Val = x.asInstanceOf[Val]

    val NDVI = Val("NDVI")
    val RED = Val("RADIOMETRY:0")
    val NIR = Val("RADIOMETRY:1")
    val BLUE = Val("RADIOMETRY:2")
    val SWIR = Val("RADIOMETRY:3")
    val SZA = Val("GEOMETRY:0")
    val SAA = Val("GEOMETRY:1")
    val SWIRVAA = Val("GEOMETRY:2")
    val SWIRVZA = Val("GEOMETRY:3")
    val VNIRVAA = Val("GEOMETRY:4")
    val VNIRVZA = Val("GEOMETRY:5")
    val SM = Val("SM")
  }
}

class ProbaVPyramidFactory(oscarsCollectionId: String, rootPath: String) extends Serializable {

  import ProbaVPyramidFactory._

  private def probaVOscarsPyramidFactory(bands: Seq[Band.Value]) = {
    val oscarsLinkTitlesWithBandIds = bands.map(b => {
      val split = b.fileMarker.split(":")
      val band = split(0)
      val index = if (split.length > 1) split(1).toInt else 0

      (band, index)
    }).groupBy(_._1)
      .map({case (k, v) => (k, v.map(_._2))})
      .toList
    new ProbavFileLayerProvider(
      oscarsCollectionId,
      NonEmptyList.fromListUnsafe(oscarsLinkTitlesWithBandIds),
      rootPath
    )
  }

  def pyramid_seq(bbox: Extent, bbox_srs: String, from_date: String, to_date: String, band_indices: java.util.List[Int]): Seq[(Int, MultibandTileLayerRDD[SpaceTimeKey])] = {
    implicit val sc: SparkContext = SparkContext.getOrCreate()

    val boundingBox = ProjectedExtent(bbox, CRS.fromName(bbox_srs))
    val from = ZonedDateTime.parse(from_date)
    val to = ZonedDateTime.parse(to_date)

    val bands: Seq[((Band.Value, Int), Int)] = bandsFromIndices(band_indices)

    val layerProvider = probaVOscarsPyramidFactory(bands.map(_._1._1))

    for (zoom <- layerProvider.maxZoom to 0 by -1)
      yield zoom -> {
        val tileLayerRdd = layerProvider.readMultibandTileLayer(from, to, boundingBox, zoom, sc)
        val orderedBandsRdd = tileLayerRdd
          .mapValues(t => MultibandTile(bands.sortBy(_._1._2).map(b => t.band(b._2)):_*))
        ContextRDD(orderedBandsRdd, tileLayerRdd.metadata)
      }
  }

  private def bandsFromIndices(band_indices: util.List[Int]): Seq[((Band.Value, Int), Int)] = {
    val bands =
      if (band_indices == null) Band.values.toSeq
        else band_indices.asScala map Band.apply

    bands.zipWithIndex.sortBy(_._1.fileMarker).zipWithIndex
  }

}
