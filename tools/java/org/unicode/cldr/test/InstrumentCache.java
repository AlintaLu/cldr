package org.unicode.cldr.test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.unicode.cldr.util.CldrUtility;

public class InstrumentCache {
    public static final boolean INSTRUMENT_CACHE = CldrUtility.getProperty("INSTRUMENT_CACHE", true);

    public static final InstrumentCache SINGLETON = new InstrumentCache();

    Map<CacheId, Map<String, Count>> cacheIdToKeyToCount = new ConcurrentHashMap<>();

    public enum CacheId {
        XPathParts
    }

    public InstrumentCache () {
        for (CacheId cacheId: CacheId.values()) {
            cacheIdToKeyToCount.put(cacheId, new ConcurrentHashMap<String, Count>());
        }
    }


    class Count {
        private int createCount;
        private int accessCount;
        private long lastAccessTime;

        public void increaseCreateCount() {
            createCount++;
        }

        public void increaseAccessCount() {
            accessCount++;
            lastAccessTime = System.currentTimeMillis();
        }

        public int getCreateCount() {
            return createCount;
        }

        public int getAccessCount() {
            return accessCount;
        }

        private long getLastAccessTime() {
            return lastAccessTime;
        }
    }

    // adds 1 to the createCount for <cacheId, key>
    public <T> void add(CacheId cacheId, String key) {
        cacheIdToKeyToCount.get(cacheId).putIfAbsent(key, new Count());
        cacheIdToKeyToCount.get(cacheId).get(key).increaseCreateCount();
    }

    // adds 1 to the accessCount for <cacheId, key>
    public <T> void access(CacheId cacheId, String key) {
        cacheIdToKeyToCount.get(cacheId).get(key).increaseAccessCount();;
    }

    // prints all the caches, but only if INSTRUMENT_CACHE.
    public void printCaches(String methodName) {
        if (!INSTRUMENT_CACHE) {
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("CacheId,key,createCount,accessCount,lastAccessTime\n");
        for (CacheId cacheId: CacheId.values()) {
            int totalCnt = 0;

            for (String key: cacheIdToKeyToCount.get(cacheId).keySet()) {

               int createCount = cacheIdToKeyToCount.get(cacheId).get(key).getCreateCount();
               int accessCount = cacheIdToKeyToCount.get(cacheId).get(key).getAccessCount();
               long lastAccessTime = cacheIdToKeyToCount.get(cacheId).get(key).getLastAccessTime();

               // For each cache, only print the items where the createCount > 1 or accessCount > 1
               if (createCount > 1 || accessCount > 1) {
                   sb.append(cacheId + "," + key.replaceAll(",", ".") + "," + createCount + "," + accessCount + "," + lastAccessTime + "\n");
               }

               totalCnt++;
            }
            // print total number of items in that cache
            sb.append(cacheId + "," + totalCnt + "\n");
        }

        // output to file
        try {
            String savePath = "***";
            Files.write(Paths.get(savePath + "instrument-cache-"
                        + methodName + "-" + java.time.LocalDateTime.now() + ".csv"),
                        sb.toString().getBytes());
        } catch (IOException e) {
            e.printStackTrace();
        }
     }
}
