package org.openeo.geotrellis.layers

import java.net.URL
import java.time.ZonedDateTime

import _root_.io.circe.parser.decode
import cats.syntax.either._
import cats.syntax.show._
import geotrellis.vector.Extent
import io.circe.generic.auto._
import io.circe.{Decoder, HCursor}

object OscarsResponses {
  implicit val decodeUrl: Decoder[URL] = Decoder.decodeString.map(new URL(_))
  implicit val decodeDate: Decoder[ZonedDateTime] = Decoder.decodeString.map{s:CharSequence => ZonedDateTime.parse(  s.toString().split('/')(0))}

  case class Link(href: URL, title: Option[String])
  case class Feature(id: String, bbox: Extent, nominalDate: ZonedDateTime, links: Array[Link], resolution: Option[Int])
  case class FeatureCollection(itemsPerPage: Int, features: Array[Feature])

  object FeatureCollection {
    def parse(json: String): FeatureCollection = {
      implicit val decodeFeature: Decoder[Feature] = new Decoder[Feature] {
        override def apply(c: HCursor): Decoder.Result[Feature] = {
          for {
            id <- c.downField("id").as[String]
            bbox <- c.downField("bbox").as[Array[Double]]
            nominalDate <- c.downField("properties").downField("date").as[ZonedDateTime]
            links <- c.downField("properties").downField("links").as[Map[String, Array[Link]]]
            resolution = c.downField("properties").downField("productInformation").downField("resolution").downArray.first.as[Int].toOption
          } yield {
            val Array(xMin, yMin, xMax, yMax) = bbox
            val extent = Extent(xMin, yMin, xMax, yMax)

            Feature(id, extent, nominalDate, links.values.flatten.toArray, resolution)
          }
        }
      }

      decode[FeatureCollection](json)
        .valueOr(e => throw new IllegalArgumentException(s"${e.show} while parsing '$json'", e))
    }
  }
}
