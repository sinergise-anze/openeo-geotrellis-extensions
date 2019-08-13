package org.openeo.geotrellissentinelhub

import java.time.{LocalDate, ZoneId}

import geotrellis.vector.Extent
import org.junit.Test
import org.openeo.geotrellissentinelhub.Gamma0Bands._

class TestS1Gamma0 {
  
  @Test
  def testGamma0(): Unit = {
    val bbox = new Extent(-5948635.289265557,-1252344.2714243263,-5792092.255337516,-1095801.2374962857)

    val date = LocalDate.of(2019, 6, 1).atStartOfDay(ZoneId.systemDefault())
    
    retrieveS1Gamma0TileFromSentinelHub(bbox, date, 256, 256, gamma0Bands)
  }

}
