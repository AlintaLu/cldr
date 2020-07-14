package org.unicode.cldr.util;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class InstrumentFileReading{
    public static final boolean INSTRUMENT_READING = CldrUtility.getProperty("INSTRUMENT_FILE_READING", false);
    public static final InstrumentFileReading SINGLETON = new InstrumentFileReading();
    class Stat {
        long fileSize;
        int loadTime;
        public Stat (long fileSize, int loadTime) {
            this.fileSize = fileSize;
            this.loadTime = loadTime;
        }
    }
    private Map<String, Stat> fileToLoadTimes = new ConcurrentHashMap<>();
    private Object SYNC = new Object();
    public void load(String fileName) {
        synchronized (SYNC) {
            if (fileToLoadTimes.containsKey(fileName)) {
                fileToLoadTimes.get(fileName).loadTime++;
            } else {
                fileToLoadTimes.put(fileName, new Stat(new File(fileName).length(), 1));
            }
        }
    }
    public void outputResult() {
        if (!INSTRUMENT_READING) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (String key: fileToLoadTimes.keySet()) {
            sb.append(key + "\t" + fileToLoadTimes.get(key).loadTime + "\t" + fileToLoadTimes.get(key).fileSize + "\n");
        }
        // output to file
        try {
            String savePath = "/usr/local/google/home/yqlu/Documents/CLDRProject/data/0713/";
            Files.write(Paths.get(savePath + "instrument-file-reading-" + java.time.LocalDateTime.now() + ".tsv"),
                        sb.toString().getBytes());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
