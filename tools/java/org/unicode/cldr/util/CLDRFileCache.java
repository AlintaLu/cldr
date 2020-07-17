package org.unicode.cldr.util;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.unicode.cldr.util.CLDRFile.DraftStatus;
import org.unicode.cldr.util.SimpleFactory.CLDRCacheKey;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

/**
 * Cache immutable CLDRFiles created from XML
 */
public class CLDRFileCache {

    public static final CLDRFileCache SINGLETON = new CLDRFileCache();

    private static final int CACHE_LIMIT = 340;

    Cache<CLDRCacheKey, CLDRFile> cache;

    public static boolean USE_CLDRFILE_CACHE = CldrUtility.getProperty("USE_CLDRFILE_CACHE", true);

    public CLDRFileCache () {
        cache =  CacheBuilder.newBuilder().maximumSize(CACHE_LIMIT).softValues().build();
    }

    public CLDRFile getCLDRFileIfPresent(String localeName, List<File> parentDirs, DraftStatus minimalDraftStatus) {
        CLDRCacheKey cacheKey = new CLDRCacheKey(localeName, false, minimalDraftStatus, parentDirs);
        CLDRFile file = cache.getIfPresent(cacheKey);
        return file;
    }

    public CLDRFile getCLDRFile (String localeName, List<File> parentDirs, DraftStatus minimalDraftStatus) {
        CLDRFile res;
        CLDRCacheKey cacheKey = new CLDRCacheKey(localeName, false, minimalDraftStatus, parentDirs);

        // Use double-check idiom
        res = cache.getIfPresent(cacheKey);

        if (res != null) {
            return res;
        }

        synchronized (cache) {
            res = cache.getIfPresent(cacheKey);
            if (res != null) {
                return res;
            }

            // make CLDRFiles created from each XMLSource and cache them
            List<CLDRFile> list = new ArrayList<>();
            for (File dir: parentDirs) {
                CLDRFile cldrFile = makeSingleCLDRFile(localeName, minimalDraftStatus, dir);
                list.add(cldrFile);
            }

            // return combinedCldrFile
            return makeCombinedCLDRFile(list, cacheKey);
        }
    }

    public CLDRFile makeSingleCLDRFile(String localeName, DraftStatus minimalDraftStatus, File dir) {
        CLDRCacheKey fileCacheKey = new CLDRCacheKey(localeName, false, minimalDraftStatus, Arrays.asList(new File[] {dir}));
        CLDRFile cldrFile = cache.getIfPresent(fileCacheKey);
        if (cldrFile == null) {
            cldrFile = SimpleFactory.makeFile(localeName, dir, minimalDraftStatus);
            // check frozen
            if (!cldrFile.isFrozen()) {
                cldrFile.freeze();
            }
            cache.put(fileCacheKey, cldrFile);
        }
        return cldrFile;
    }

    public CLDRFile makeCombinedCLDRFile(List<CLDRFile> list, CLDRCacheKey cacheKey) {
        if (list.size() == 1) {
            return list.get(0);
        }
        // merge all CLDRFiles into one CLDRFile
        CLDRFile combinedCLDRFile = list.get(0).cloneAsThawed();
        for (int i = 1; i < list.size(); i++) {
            CLDRFile other = list.get(i);
            combinedCLDRFile.putAll(other, CLDRFile.MERGE_KEEP_MINE);
            combinedCLDRFile.dataSource.getXpathComments().joinAll(other.dataSource.getXpathComments());
        }
        combinedCLDRFile.freeze();
        cache.put(cacheKey, combinedCLDRFile);
        return combinedCLDRFile;
    }
}