package org.unicode.cldr.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Instrument isOptional(...) method in LogicalGrouping.java
 * @author Yiqing Lu
 *
 */
public class InstrumentIsOptional {
    public static enum PartInIsOptional{
//        XPATHPARTS_GET_INSTANCE (0, "XPathParts.getInstance(path)"),
        XPATHPARTS_GET_FPOZENINSTANCE (0, "XPathParts.getFrozenInstance(path)"),
        RELATIVE(1, "parts.containsElement(\"relative\")"),
        PATH_COUNT(2, "path with/without count"),
        GET_PLURALRULE(3, "getPluralRule"),
        CLONE_AS_THAWED(4, "cloneAsThawed"),
        CHECK_SUPPRESSED(5, "checkIfSuppressed"),
        TOTAL_SPENDING(6, "TotalTimeSpendingInMethod");

        public static int length = PartInIsOptional.values().length;

        private int index;
        private String partName;

        PartInIsOptional(int index, String partName) {
            this.index = index;
            this.partName = partName;
        }

        public int getIndex() {
            return index;
        }

        public String getPartName() {
            return partName;
        }
    }

    /**
     * Instrument method isOptional when INSTRUMENT_ISOPTIONAL is true.
     * for each isOptionalTime[i] array, isOptionalTime[i][0] is the total time spending
     * in this part, isOptionalTime[i][1] is the total times entering this part.
     */
    private static long[][] isOptionalTime = new long[PartInIsOptional.length][2];
    public static final boolean INSTRUMENT_ISOPTIONAL = true;

    public static void recordPart(PartInIsOptional part, long time) {
        isOptionalTime[part.getIndex()][0] += time;
        isOptionalTime[part.getIndex()][1]++;
    }

    public static void outPutInstrumentResult() {
        if (!INSTRUMENT_ISOPTIONAL) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Part Name\tAvg(microsecond)\tTotal(microsecond)\tEnter times\n");
        for (PartInIsOptional part: PartInIsOptional.values()) {
            int index = part.getIndex();
            String partName = part.getPartName();
            if (isOptionalTime[index][1] == 0) {
                sb.append(partName + "\tno record\n");
            } else {
                sb.append(partName + "\t" + (1.0 * 1000* isOptionalTime[index][0] / isOptionalTime[index][1]) + "\t"
                    + 1000 * isOptionalTime[index][0] + "\t" +isOptionalTime[index][1] + "\n");
            }
            // clear array for later reloading and recording
            isOptionalTime[index][0] = 0;
            isOptionalTime[index][1] = 0;
        }

        // output to file
        try {
            String savePath = "***";
            Files.write(Paths.get(savePath + "instrument-isOptional-" + java.time.LocalDateTime.now() + ".tsv"),
                        sb.toString().getBytes());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
