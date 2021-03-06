/*
 * Copyright [2016] Charlie Black
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.geode.geospatial.function;

import com.google.common.collect.Lists;
import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.io.WKTReader;
import org.apache.geode.cache.Region;
import org.apache.geode.cache.client.ClientCacheFactory;
import org.apache.geode.cache.client.Pool;
import org.apache.geode.cache.execute.*;
import org.apache.geode.geospatial.domain.LocationEvent;
import org.apache.geode.geospatial.index.GeospatialIndex;
import org.apache.geode.pdx.PdxInstance;
import org.springframework.beans.factory.annotation.Required;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * This is the server side function that uses the index to find items that are stored in Geode
 *
 * Created by Charlie Black on 9/23/16.
 */
public class GeoQueryFunction implements Function {
    public static final String ID = "geoQueryFunction";

    private int chunkSize = 1000;
    private GeospatialIndex<Object, LocationEvent> geospatialIndex;
    private Region<Object, PdxInstance> region;

    @Required
    public void setChunkSize(int chunkSize) {
        this.chunkSize = chunkSize;
    }

    @Required
    public void setGeospatialIndex(GeospatialIndex<Object, LocationEvent> geospatialIndex) {
        this.geospatialIndex = geospatialIndex;
    }

    @Required
    public void setRegion(Region<Object, PdxInstance> region) {
        this.region = region;
    }

    @Override
    public void execute(FunctionContext context) {

        ResultSender<Collection<PdxInstance>> resultSender = context.getResultSender();
        try {
            String wellKownText = (String) context.getArguments();
            //Create a JTS object that we can test against.
            Geometry geometry = new WKTReader().read(wellKownText);

            ArrayList<Object> keys = new ArrayList<Object>(geospatialIndex.query(geometry));

            List<List<Object>> partitionedKeys = Lists.partition(keys, chunkSize);
            for (List currKeySet : partitionedKeys) {
                resultSender.sendResult(new ArrayList<>(region.getAll(currKeySet).values()));
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
        resultSender.lastResult(null);

    }

    public static final List<PdxInstance> query(String wellKnowText) {

        //Use the default connection pool to make the request on.   If we were connected to more then one
        //distributed system we would have ask for right system.   Since we are connected to only one we are fine
        // with default.
        Pool pool = ClientCacheFactory.getAnyInstance().getDefaultPool();
        //Since the spatial data is large and it partitioned over N servers we need to query all of the servers at the
        //same time.   On servers tell GemFire to execute the specifed function on all servers.
        Execution execution = FunctionService.onServers(pool).withArgs(wellKnowText);
        //We cause the execution to happen asynchronously
        Collection<Collection<PdxInstance>> resultCollector = (Collection<Collection<PdxInstance>>) execution.execute(ID).getResult();

        ArrayList<PdxInstance> results = new ArrayList<>();

        resultCollector.forEach(pdxInstanceCollection -> {
            if (pdxInstanceCollection != null) {
                pdxInstanceCollection.forEach(locationEvent -> {
                    if (locationEvent != null) {
                        results.add(locationEvent);
                    }
                });
            }
        });

        return results;

    }

    @Override
    public boolean hasResult() {
        return true;
    }

    @Override
    public String getId() {
        return ID;
    }
}
