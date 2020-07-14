package org.unicode.cldr.util;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class InstrumentFileReading{
    public static final boolean INSTRUMENT_READING = CldrUtility.getProperty("INSTRUMENT_FILE_READING", true);
    public static final InstrumentFileReading SINGLETON = new InstrumentFileReading();
    class Stat {
        long fileSize;
        int loadTime = 0;
        private Object SYNC = new Object();
        public Stat (long fileSize) {
            this.fileSize = fileSize;
        }
        public void increaseLoadTime() {
            synchronized(SYNC) {
                loadTime++;
            }
        }
    }

    public enum ReadId {
        loadFromInputStream,
        XMLFileReaderAnnotation,
        XMLFileReaderLoadPathValues
    }
    private Map<ReadId, Map<String, Stat>> readIdTofileToLoadTimes = new ConcurrentHashMap<>();
    public InstrumentFileReading () {
        for (ReadId id: ReadId.values()) {
            readIdTofileToLoadTimes.put(id, new ConcurrentHashMap<String, Stat>());
        }
    }
    public void load(ReadId id, String fileName) {
        readIdTofileToLoadTimes.get(id).putIfAbsent(fileName, new Stat(new File(fileName.split(",")[0]).length()));
        readIdTofileToLoadTimes.get(id).get(fileName).increaseLoadTime();
    }
    public void outputResult(String methodName) {
        if (!INSTRUMENT_READING) {
            return;
        }
        for (ReadId id: readIdTofileToLoadTimes.keySet()) {
            StringBuilder sb = new StringBuilder();
            Map<String, Stat> map = readIdTofileToLoadTimes.get(id);
            for (String key: map.keySet()) {
                sb.append(key + "\t" + map.get(key).loadTime + "\t" + map.get(key).fileSize + "\n");
            }
            // output to file
            try {
                String savePath = "/usr/local/google/home/yqlu/Documents/CLDRProject/data/0714/";
                Files.write(Paths.get(savePath + "instrument-file-reading-" + id + "-" + methodName + "-" + java.time.LocalDateTime.now() + ".tsv"),
                            sb.toString().getBytes());
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
